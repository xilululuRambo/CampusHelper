package com.rambo.module.goods.server.job;

import cn.hutool.core.bean.BeanUtil;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsSyncRetry;
import com.rambo.infrastructure.search.EsSyncRetryService;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.infrastructure.storage.OssAsyncUtil;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.server.service.GoodsService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.List;

/**
 * XXL-JOB：重试商品 ES 索引同步（t_es_sync_retry 中 dataType=goods 的记录）
 *
 * <p>实体存在 → 同步索引；实体已被删除 → 清除 ES 数据 + 异步删除 OSS 文件；
 * 单条失败 → 重试次数 +1，超 5 次终止。</p>
 */
@Slf4j
@Component
public class GoodsEsRetryJob {

    @Resource
    private EsSyncRetryService esRetryService;
    @Resource
    private GoodsService goodsService;
    @Resource
    private EsUtil esUtil;
    @Resource
    private OssAsyncUtil ossAsyncUtil;

    @XxlJob("goodsEsRetryJob")
    public void execute() {
        // 1. 查询商品类型的待重试数据
        List<EsSyncRetry> list = esRetryService.getWaitRetryList("goods");
        if (list.isEmpty()) {
            return;
        }

        // 2. 逐条重试
        for (EsSyncRetry retry : list) {
            try {
                boolean dataExists = true;
                // 查商品
                Goods goods = goodsService.getById(retry.getDataId());

                // 判断商品是否存在，不存在则删除 ES 数据
                if (goods == null) {
                    dataExists = false;
                } else {
                    EsDTO esDTO = BeanUtil.copyProperties(goods, EsDTO.class);
                    // 枚举无法自动转 Integer，手动设置状态码（ES 过滤必需）
                    esDTO.setStatus(goods.getStatus() != null ? goods.getStatus().getCode() : null);
                    esUtil.saveGoods(esDTO);
                }

                // 实体不存在：删除 ES 数据 + 异步删除 OSS 文件
                if (!dataExists) {
                    esUtil.deleteGoods(retry.getDataId());
                    String fileUrls = retry.getFileUrls();
                    if (StringUtils.hasText(fileUrls)) {
                        List<String> fileList = Arrays.asList(fileUrls.split(","));
                        ossAsyncUtil.deleteFilesAsync(fileList, "goods", retry.getDataId());
                    }
                }

                // 成功：标记成功
                esRetryService.markSuccess(retry.getId());
            } catch (Exception e) {
                // 失败次数+1，超过5次停止
                esRetryService.incrRetryCount(retry.getId(), e.getMessage());
                log.error("商品ES重试失败，dataId：{}", retry.getDataId(), e);
            }
        }

        XxlJobHelper.handleSuccess("处理完成，共重试 " + list.size() + " 条商品同步记录");
    }
}
