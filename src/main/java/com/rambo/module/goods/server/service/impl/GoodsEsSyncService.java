package com.rambo.module.goods.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsSyncRetryService;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.module.goods.pojo.entity.Goods;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 商品 ES 索引同步组件（业务层编排：商品实体 → EsDTO → EsUtil 写入，失败写重试表）。
 *
 * <p>由基础设施层 {@code EsAsyncUtil} 拆出并下沉到业务层：商品与任务各自的同步逻辑
 * 本质是业务编排（依赖业务实体 Goods），不属通用能力，归各业务模块所有，
 * 使基础设施层对业务模块保持零依赖。</p>
 */
@Component
@Slf4j
public class GoodsEsSyncService {
    @Resource
    private EsSyncRetryService esSyncRetryService;

    @Resource
    private EsUtil esUtil;

    /**
     * 统一异步线程池（AsyncConfig 的 taskExecutor）：
     * 替代 CompletableFuture 默认的 ForkJoinPool.commonPool——后者丢失 MDC traceId 且可被
     * 并行流/其他任务饱和；taskExecutor 带 MDC 装饰器，可完整透传日志链路。
     */
    @Resource(name = "taskExecutor")
    private Executor taskExecutor;

    /**
     * 异步同步商品到ES
     *
     * @param goods 商品实体类
     */
    public void syncToEsAsync(Goods goods) {
        // 版本号必须在调用线程（业务线程）取值：异步线程内的 System.currentTimeMillis()
        // 无法反映业务事件发生顺序，乱序执行时旧任务的时间戳反而更大，会覆盖新数据
        long version = System.currentTimeMillis();
        CompletableFuture.runAsync(() -> {
            try {
                EsDTO esDTO = BeanUtil.copyProperties(goods, EsDTO.class);
                // 枚举无法自动转 Integer，手动设置状态码（ES 过滤必需）
                esDTO.setStatus(goods.getStatus() != null ? goods.getStatus().getCode() : null);
                esDTO.setUpdateTime(version);
                esUtil.saveGoods(esDTO);
            } catch (ElasticsearchException e) {
                if (e.status() == 409) {
                    // 外部版本冲突：本次为旧版本数据，ES 已拒绝覆盖（防乱序的预期行为），忽略即可
                    log.debug("ES 版本冲突（旧数据），忽略覆盖 goodsId={}, version={}", goods.getId(), version);
                } else {
                    log.error("ES 索引失败，goodsId={}，等待定时任务补偿", goods.getId(), e);
                    esSyncRetryService.addRetry(goods.getId(), PrefixConstants.GOODS_TYPE, goods.getImages(), e.getMessage());
                }
            } catch (Exception e) {
                // 覆盖 IOException 之外的所有异常（序列化失败/连接层 RuntimeException 等），
                // 修复原实现"只 catch IOException 导致异常静默穿透、重试表永远不写"的问题
                log.error("ES 索引失败，goodsId={}，等待定时任务补偿", goods.getId(), e);
                esSyncRetryService.addRetry(goods.getId(), PrefixConstants.GOODS_TYPE, goods.getImages(), e.getMessage());
            }
        }, taskExecutor);
    }

    /**
     * 异步删除商品ES
     */
    public void deleteGoodsFromEsAsync(Long goodsId, String images) {
        CompletableFuture.runAsync(() -> {
            try {
                esUtil.deleteGoods(goodsId);
            } catch (Exception e) {
                log.error("ES商品删除失败 id:{}", goodsId, e);
                // 失败写入重试表
                esSyncRetryService.addRetry(goodsId, PrefixConstants.GOODS_TYPE, images, e.getMessage());
            }
        }, taskExecutor);
    }
}
