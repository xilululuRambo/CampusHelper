package com.rambo.infrastructure.search;

import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import com.rambo.common.enumType.EsSyncOp;
import com.rambo.infrastructure.database.TransactionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * ES 事务性 Outbox 同步基类（基础设施层，零业务依赖）。
 *
 * <p>调用方（业务 Service）在<b>数据库事务内</b>调用 {@link #enqueueUpsert}/{@link #enqueueDelete}：
 * 同步意图随业务写库一并原子落库，事务提交后（{@link TransactionUtils#afterCommit}）再异步派发，
 * 因此不存在「业务已提交但同步意图丢失」的窗口。异步派发失败不影响业务，交由 XXL-JOB 兜底重试。</p>
 *
 * <p>子类只需实现三件事：数据类型标识、回源加载实体（转 {@link EsDTO}）、以及 ES 读写原语。
 * 基类不感知任何业务实体，保持「基础设施 → 业务」零依赖的架构约束。</p>
 */
@Slf4j
public abstract class AbstractEsOutboxSyncService {

    @Resource
    protected EsSyncOutboxService esSyncOutboxService;

    @Resource(name = "taskExecutor")
    protected Executor taskExecutor;

    /** 数据类型标识（goods/task），与发件箱 dataType、定时任务查询保持一致 */
    protected abstract String dataType();

    /**
     * 回源加载最新实体并转换为文档；实体不存在（或已逻辑删除）时返回 {@code null}（触发删除）。
     */
    protected abstract EsDTO loadEsDTO(Long dataId);

    /** 写入/更新 ES 文档；{@link EsDTO#getUpdateTime()} 已由基类置为本次版本号 */
    protected abstract void saveEsDoc(EsDTO esDTO) throws Exception;

    /** 按外部版本号删除 ES 文档 */
    protected abstract void deleteEsDoc(Long dataId, long version) throws Exception;

    /**
     * 业务事务内登记一次「写入/更新」意图，提交后异步派发。
     */
    public void enqueueUpsert(Long dataId, String fileUrls) {
        esSyncOutboxService.enqueueUpsert(dataId, dataType(), fileUrls);
        TransactionUtils.afterCommit(() -> triggerAsync(dataId));
    }

    /**
     * 业务事务内登记一次「删除」意图，提交后异步派发。
     */
    public void enqueueDelete(Long dataId, String fileUrls) {
        esSyncOutboxService.enqueueDelete(dataId, dataType(), fileUrls);
        TransactionUtils.afterCommit(() -> triggerAsync(dataId));
    }

    private void triggerAsync(Long dataId) {
        CompletableFuture.runAsync(() -> dispatch(dataId), taskExecutor);
    }

    /**
     * 派发单条发件箱：以行内 <b>当前</b> 版本号为准（派发时重新读取，天然合并已入队的更新意图）。
     *
     * <p>执行顺序：ES 写入/删除 → 清理随行登记的外部文件（OSS）→ 标记成功。
     * 任一步骤失败都计数重试（ES 写入幂等、OSS 删除幂等，可安全重跑）。
     * 幂等、可重入：异步派发与定时补偿都调用本方法，重复执行安全。</p>
     */
    public void dispatch(Long dataId) {
        EsSyncOutbox row = esSyncOutboxService.getPending(dataType(), dataId);
        if (row == null) {
            // 已被更新意图取代、或已同步完成
            return;
        }
        long version = row.getEsVersion();
        boolean isDelete = EsSyncOp.DELETE.equals(row.getOpType());
        try {
            try {
                if (isDelete) {
                    deleteEsDoc(dataId, version);
                } else {
                    EsDTO esDTO = loadEsDTO(dataId);
                    if (esDTO == null) {
                        // 意图是写入，但实体已不存在（如已删除）：降级为删除，避免 ES 残留脏数据
                        deleteEsDoc(dataId, version);
                    } else {
                        esDTO.setUpdateTime(version);
                        saveEsDoc(esDTO);
                    }
                }
            } catch (Exception e) {
                int status = esErrorStatus(e);
                // 409：本次版本低于 ES 现值，说明已有更新版本落库，本意图被取代，视为达成；
                // 404（删除）：删除目标不存在，同样视为已达成
                if (status == 409 || (isDelete && status == 404)) {
                    log.info("ES 同步已达成（被更新版本取代/目标不存在），dataType={}，dataId={}，status={}",
                            dataType(), dataId, status);
                } else {
                    throw e;
                }
            }
            // ES 已达成：清理随行登记的外部文件（OSS）；清理失败则整体重试，ES 侧幂等安全
            deleteExternalFiles(splitFileUrls(row.getFileUrls()));
            esSyncOutboxService.markSuccess(row.getId(), version);
        } catch (Exception e) {
            log.error("ES 同步失败，dataType={}，dataId={}，等待定时补偿", dataType(), dataId, e);
            esSyncOutboxService.incrRetryCount(row.getId(), version, e.getMessage());
        }
    }

    /**
     * 从 ES 异常中提取 HTTP 状态码；无法识别时返回 {@code -1}（即「不是可忽略的版本冲突」）。
     *
     * <p><b>为什么不能只 catch {@code ElasticsearchException}（本次端到端测试挖出的真实 bug）</b>：
     * 本项目用 {@code RestClientTransport} 构造 {@code ElasticsearchClient}，其 4xx/5xx
     * 响应是以 {@link org.elasticsearch.client.ResponseException} 抛出的——该类型
     * <b>继承 {@code java.io.IOException}</b>；而
     * {@code co.elastic.clients.elasticsearch._types.ElasticsearchException}
     * <b>继承 {@code RuntimeException}</b>。二者属于完全不相干的两条继承链，
     * 因此原实现里 {@code catch (ElasticsearchException)} <b>在真实 ES 上永不命中</b>：
     * 版本冲突（409）会被当成普通失败计入重试，反复重投直到 {@code retryCount}
     * 触顶被标记 FAILED，而真正的语义是「本次意图已被更新版本取代，属达成」。</p>
     *
     * <p>本方法把两种形态统一成状态码：{@code ResponseException} 直接读
     * {@code getResponse().getStatusLine().getStatusCode()}；若调用方换回会抛
     * {@code ElasticsearchException} 的 transport，则读其 {@code status()}。
     * 这样无论底层客户端如何包装，判定逻辑都只有一份。</p>
     */
    private static int esErrorStatus(Exception e) {
        if (e instanceof org.elasticsearch.client.ResponseException re) {
            return re.getResponse().getStatusLine().getStatusCode();
        }
        if (e instanceof ElasticsearchException ee) {
            return ee.status();
        }
        return -1;
    }

    /**
     * 清理随行登记的外部文件（默认不处理）。
     *
     * <p>需清理外部存储（如 OSS 商品旧图 / 删除的商品图）的子类覆写此方法；
     * 清理失败应抛出异常，由基类统一计数重试。</p>
     */
    protected void deleteExternalFiles(List<String> fileUrls) throws Exception {
        // 默认无外部文件需要清理
    }

    /**
     * 将逗号分隔的文件名拆分为去重列表（去空白、过滤空项）。
     */
    private List<String> splitFileUrls(String fileUrls) {
        if (!StringUtils.hasText(fileUrls)) {
            return Collections.emptyList();
        }
        return Arrays.stream(fileUrls.split(","))
                .map(String::trim)
                .filter(StringUtils::hasText)
                .distinct()
                .toList();
    }
}
