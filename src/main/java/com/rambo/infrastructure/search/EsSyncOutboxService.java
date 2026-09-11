package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.enumType.EsSyncOp;
import com.rambo.common.enumType.RetryStatus;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashSet;
import java.util.List;

/**
 * ES 同步发件箱服务（事务性 Outbox 的存取门面）。
 *
 * <p>单实现服务：接口与实现已合并为一层，直接作为 Spring Bean 注入使用，减少一次无意义的跳转。</p>
 *
 * <p>职责：业务事务内调用 {@link #enqueueUpsert}/{@link #enqueueDelete} 登记同步意图；
 * 异步派发或定时补偿时用 {@link #getPending}/{@link #getWaitList} 取出待同步行，
 * 完成后用 {@link #markSuccess} 置成功、失败用 {@link #incrRetryCount} 计数并在超限时终止。</p>
 *
 * <p>所有终态回写都带版本号条件：仅当行内版本仍为本次处理所用版本时才更新，
 * 防止「处理旧版本的过程中被新意图覆盖」这一竞态把新意图误标为成功。</p>
 *
 * <p>入队以 {@code dataType + dataId} 为幂等键做合并写：先按唯一键探测是否已存在，
 * 存在则原地更新为新意图并刷新版本号；不存在则插入，并发插入时由唯一索引兜底
 * （{@link DuplicateKeyException} 被捕获后转为更新），避免重复行。</p>
 */
@Slf4j
@Service
public class EsSyncOutboxService extends ServiceImpl<EsSyncOutboxMapper, EsSyncOutbox> {

    /**
     * 重试次数阈值：超过后标记 FAILED 终止，等待人工介入
     */
    private static final int MAX_RETRY_COUNT = 5;

    @Resource
    private EsVersionGenerator versionGenerator;

    /**
     * 登记一次「写入/更新」意图（同一 dataType+dataId 合并为一行，覆盖旧意图并刷新版本号）。
     */
    public void enqueueUpsert(Long dataId, String dataType, String fileUrls) {
        enqueue(dataId, dataType, EsSyncOp.UPSERT, fileUrls);
    }

    /**
     * 登记一次「删除」意图（同一 dataType+dataId 合并为一行，覆盖旧意图并刷新版本号）。
     */
    public void enqueueDelete(Long dataId, String dataType, String fileUrls) {
        enqueue(dataId, dataType, EsSyncOp.DELETE, fileUrls);
    }

    private void enqueue(Long dataId, String dataType, EsSyncOp opType, String fileUrls) {
        // 版本号在业务线程取值：以入队顺序为准，保证「后发生的意图版本更大」
        long version = versionGenerator.next();
        EsSyncOutbox exist = lambdaQuery()
                .eq(EsSyncOutbox::getDataType, dataType)
                .eq(EsSyncOutbox::getDataId, dataId)
                .one();
        if (exist != null) {
            // 待清理文件取并集：既有未清理文件 + 本次新登记文件，避免连续更新丢失待删旧图
            updateEnqueue(exist.getId(), opType, version, mergeFileUrls(exist.getFileUrls(), fileUrls));
            return;
        }
        EsSyncOutbox row = new EsSyncOutbox();
        row.setDataId(dataId);
        row.setDataType(dataType);
        row.setOpType(opType);
        row.setEsVersion(version);
        row.setFileUrls(mergeFileUrls(null, fileUrls));
        row.setRetryCount(0);
        row.setStatus(RetryStatus.PENDING);
        try {
            save(row);
        } catch (DuplicateKeyException e) {
            // 并发窗口内对方已插入（唯一索引兜底），退化为更新，避免重复行
            EsSyncOutbox current = lambdaQuery()
                    .eq(EsSyncOutbox::getDataType, dataType)
                    .eq(EsSyncOutbox::getDataId, dataId)
                    .one();
            if (current != null) {
                updateEnqueue(current.getId(), opType, version, mergeFileUrls(current.getFileUrls(), fileUrls));
            } else {
                log.warn("发件箱合并写异常：唯一键冲突但查询不到记录，dataType={}，dataId={}", dataType, dataId);
            }
        }
    }

    /**
     * 将已存在的行刷新为最新意图：覆盖操作类型/版本号/文件地址，重置重试次数与状态。
     */
    private void updateEnqueue(Long id, EsSyncOp opType, long version, String fileUrls) {
        lambdaUpdate()
                .eq(EsSyncOutbox::getId, id)
                .set(EsSyncOutbox::getOpType, opType)
                .set(EsSyncOutbox::getEsVersion, version)
                .set(EsSyncOutbox::getFileUrls, fileUrls)
                .set(EsSyncOutbox::getRetryCount, 0)
                .set(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .set(EsSyncOutbox::getErrorMsg, null)
                .update();
    }

    /**
     * 合并待清理文件：返回「既有 + 本次」的并集（去重保序）。
     *
     * <p>同一 (dataType, dataId) 只保留一行，多次意图会合并覆盖；若直接覆盖 fileUrls，
     * 上一次登记但尚未清理的文件会丢失，导致 OSS 残留。故这里取并集；同步成功后由
     * {@link #markSuccess} 清空该列，避免 SUCCESS 行的旧文件被后续意图反复累积。</p>
     */
    private String mergeFileUrls(String existFileUrls, String newFileUrls) {
        boolean existEmpty = !StringUtils.hasText(existFileUrls);
        boolean newEmpty = !StringUtils.hasText(newFileUrls);
        if (existEmpty) {
            return newEmpty ? null : newFileUrls.trim();
        }
        if (newEmpty) {
            return existFileUrls;
        }
        LinkedHashSet<String> merged = new LinkedHashSet<>();
        for (String item : existFileUrls.split(",")) {
            if (StringUtils.hasText(item)) {
                merged.add(item.trim());
            }
        }
        for (String item : newFileUrls.split(",")) {
            if (StringUtils.hasText(item)) {
                merged.add(item.trim());
            }
        }
        return merged.isEmpty() ? null : String.join(",", merged);
    }

    /**
     * 查询指定数据类型的待同步列表（按主键升序，最多 limit 条）。
     */
    public List<EsSyncOutbox> getWaitList(String dataType, int limit) {
        return lambdaQuery()
                .eq(EsSyncOutbox::getDataType, dataType)
                .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .orderByAsc(EsSyncOutbox::getId)
                .last("limit " + limit)
                .list();
    }

    /**
     * 查询指定实体的待同步行；不存在或已不是待同步状态时返回 null。
     */
    public EsSyncOutbox getPending(String dataType, Long dataId) {
        return lambdaQuery()
                .eq(EsSyncOutbox::getDataType, dataType)
                .eq(EsSyncOutbox::getDataId, dataId)
                .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .one();
    }

    /**
     * 标记成功（仅当行内版本 == version 时生效）。
     */
    public void markSuccess(Long id, Long version) {
        // 版本号条件：若处理期间有更新意图入队（版本已增大），本次结果作废，不误标新意图为成功
        lambdaUpdate()
                .eq(EsSyncOutbox::getId, id)
                .eq(EsSyncOutbox::getEsVersion, version)
                .set(EsSyncOutbox::getStatus, RetryStatus.SUCCESS)
                // 清空待清理文件：避免 SUCCESS 行残留旧文件，被后续意图取并集时反复累积、重复删除
                .set(EsSyncOutbox::getFileUrls, null)
                .update();
    }

    /**
     * 重试次数+1，超限标记终止（仅当行内版本 == version 时生效）。
     */
    public void incrRetryCount(Long id, Long version, String errorMsg) {
        lambdaUpdate()
                .eq(EsSyncOutbox::getId, id)
                .eq(EsSyncOutbox::getEsVersion, version)
                .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .setSql("retry_count = retry_count + 1")
                .set(EsSyncOutbox::getErrorMsg, errorMsg)
                .update();

        // 超过阈值标记终止（同样带版本号条件，避免误伤更新的意图）
        lambdaUpdate()
                .eq(EsSyncOutbox::getId, id)
                .eq(EsSyncOutbox::getEsVersion, version)
                .ge(EsSyncOutbox::getRetryCount, MAX_RETRY_COUNT)
                .set(EsSyncOutbox::getStatus, RetryStatus.FAILED)
                .update();
    }
}
