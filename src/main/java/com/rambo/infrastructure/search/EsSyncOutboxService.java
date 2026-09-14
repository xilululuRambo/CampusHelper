package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.enumType.EsSyncOp;
import com.rambo.common.enumType.RetryStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * ES 同步发件箱服务（事务性 Outbox 的存取门面）。
 *
 * <p>单实现服务：接口与实现已合并为一层，直接作为 Spring Bean 注入使用，减少一次无意义的跳转。</p>
 *
 * <p>职责：业务事务内调用 {@link #enqueueUpsert}/{@link #enqueueDelete} 登记同步意图；
 * 异步派发或定时补偿时用 {@link #getWaitList} 取出待同步行，
 * 完成后用 {@link #markSuccess} 置成功、失败用 {@link #incrRetryCount} 计数并在超限时终止。</p>
 *
 * <p><b>每条 enqueue 独立成行</b>：行 id 即 ES 外部版本号（自增单调），保证后到的写入版本天然更大，
 * 旧事件会被 ES 以 409 拒绝，从根上杜绝乱序覆盖。</p>
 *
 * <p><b>重试按指数退避</b>：失败后 {@link #incrRetryCount} 把 {@code nextRetryAt} 推后为
 * {@code NOW + min(30 * 2^retryCount, 600)} 秒；{@code getWaitList} 通过
 * {@code nextRetryAt <= NOW} 过滤冷却期内的行，避免雪崩式重投。</p>
 */
@Slf4j
@Service
public class EsSyncOutboxService extends ServiceImpl<EsSyncOutboxMapper, EsSyncOutbox> {

    /**
     * 重试次数阈值：超过后标记 FAILED 终止，等待人工介入
     */
    private static final int MAX_RETRY_COUNT = 5;

    /** 重试退避基准（秒） */
    private static final long RETRY_BASE_SECONDS = 30L;
    /** 重试退避上限（秒）：封顶 10 分钟 */
    private static final long RETRY_CAP_SECONDS = 600L;

    /**
     * 登记一次「写入/更新」意图——<b>总是新插入一行</b>，行 id 作为 ES 外部版本号。
     *
     * @return 新插入行的 id（用于后续精确派发）
     */
    public Long enqueueUpsert(Long dataId, String dataType, String fileUrls) {
        return enqueue(dataId, dataType, EsSyncOp.UPSERT, fileUrls);
    }

    /**
     * 登记一次「删除」意图——<b>总是新插入一行</b>，行 id 作为 ES 外部版本号。
     *
     * @return 新插入行的 id（用于后续精确派发）
     */
    public Long enqueueDelete(Long dataId, String dataType, String fileUrls) {
        return enqueue(dataId, dataType, EsSyncOp.DELETE, fileUrls);
    }

    private Long enqueue(Long dataId, String dataType, EsSyncOp opType, String fileUrls) {
        EsSyncOutbox row = new EsSyncOutbox();
        row.setDataId(dataId);
        row.setDataType(dataType);
        row.setOpType(opType);
        row.setFileUrls(fileUrls);
        row.setRetryCount(0);
        row.setStatus(RetryStatus.PENDING);
        // 新登记行立即可派发：next_retry_at = NOW()
        row.setNextRetryAt(nowMillis());
        save(row);
        return row.getId();
    }

    /**
     * 取当前时刻并<b>向下截断到毫秒</b>，与列类型 {@code DATETIME(3)} 对齐。
     *
     * <p><b>为什么必须截断（真实踩坑）</b>：{@code next_retry_at} 曾用 {@code DATETIME(0)}，
     * 而 MySQL 对小数秒是<b>四舍五入</b>而非截断——写入 {@code 16:50:39.797} 会被存成
     * {@code 16:50:40}。此时 {@link #getWaitList} 用「同一秒内的真实时刻」
     * （如 {@code 16:50:39.806}）去比较，就会得到 {@code 16:50:40 <= 16:50:39.806 = false}：
     * <b>刚登记的行当场查不出来</b>，只能等下一轮调度才被拾取。</p>
     *
     * <p>截断后「存储值 ≤ 真实时刻」恒成立，因此任何发生在写入之后的查询都必然能命中该行，
     * 行不会被推迟到退避窗口之外。退避值（≥30s）的毫秒级误差无实际影响。</p>
     */
    private static LocalDateTime nowMillis() {
        return LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
    }

    /**
     * 查询指定数据类型的待同步列表（按主键升序，最多 limit 条）。
     * <p><b>冷却过滤</b>：仅返回 {@code nextRetryAt <= NOW} 的行——尚在指数退避窗口内的失败行
     * 不会被立即拉回，避免雪崩式重投。</p>
     */
    public List<EsSyncOutbox> getWaitList(String dataType, int limit) {
        return lambdaQuery()
                .eq(EsSyncOutbox::getDataType, dataType)
                .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .le(EsSyncOutbox::getNextRetryAt, LocalDateTime.now())
                .orderByAsc(EsSyncOutbox::getId)
                .last("limit " + limit)
                .list();
    }

    /**
     * 标记成功（仅当当前状态仍为 PENDING 时生效）。
     * <p>不需版本号条件：每行 id 唯一，对该 id 的 markSuccess 是天然幂等的；
     * 若行已不在 PENDING（被手动改过、或 FAILED 终态），本方法不做任何更改。</p>
     */
    public void markSuccess(Long id) {
        lambdaUpdate()
                .eq(EsSyncOutbox::getId, id)
                .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .set(EsSyncOutbox::getStatus, RetryStatus.SUCCESS)
                // 清空待清理文件：本行的 OSS 文件已删完，置空避免 SUCCESS 行长期残留垃圾数据
                .set(EsSyncOutbox::getFileUrls, null)
                .update();
    }

    /**
     * 重试次数+1，并把 {@code nextRetryAt} 推到指数退避后的下一时刻；超限标记 FAILED 终止。
     *
     * <p>退避公式：{@code nextRetryAt = NOW + min(30 * 2^retryCount, 600)} 秒——
     * 30 / 60 / 120 / 240 / 480 / 600 / 600 ...，封顶 10 分钟。冷却期间
     * {@link #getWaitList} 不会拉回该行，达到限流目的。</p>
     *
     * <p><b>两步写</b>：先原子地把 retry_count+1 并把 nextRetryAt 推到 NOW+退避秒数，
     * 再单独判断「当前 retry_count 是否达到 MAX_RETRY_COUNT」并置 FAILED。
     * 拆成两步是为了避免在同一 SQL 里同时读 retry_count 又写它（MySQL UPDATE
     * 不能引用同表子查询的副作用——拆分后 retry_count 自增 +1 已是持久化结果，
     * 第二步只是顺手把状态翻到终态）。</p>
     */
    public void incrRetryCount(Long id, String errorMsg) {
        // ① 自增计数 + 推进 nextRetryAt（基于「自增后的 retryCount」算退避）
        //    退避长度先在 SQL 之外算好作为常量塞进去，避免两次自增之间的竞态
        int newRetryCount = countAndAdvanceNextRetryAt(id, errorMsg);
        if (newRetryCount < 0) {
            // 行不存在或已被改出 PENDING（理论上不应发生，防御性日志）
            log.warn("incrRetryCount 未命中 PENDING 行，id={}", id);
            return;
        }
        // ② 达到上限 → 标记 FAILED 终止
        if (newRetryCount >= MAX_RETRY_COUNT) {
            lambdaUpdate()
                    .eq(EsSyncOutbox::getId, id)
                    .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                    .set(EsSyncOutbox::getStatus, RetryStatus.FAILED)
                    .update();
        }
    }

    /**
     * 在一次 UPDATE 里把 retry_count+1 并把 nextRetryAt 推到 NOW + 退避秒数，返回自增后的新值。
     * 行不在 PENDING 或不存在时返回 -1。
     *
     * <p>退避秒数基于「自增前」的 retryCount 算——retryCount=0 失败后等 30s、
     * retryCount=1 失败后等 60s……与「失败次数」对齐，符合常规语义。</p>
     */
    private int countAndAdvanceNextRetryAt(Long id, String errorMsg) {
        // 先读当前 retryCount 用于算退避（同一行的两次操作在并发下不严格原子，但 outbox 行
        // 由触发异步派发的同一线程流转，串行窗口里 retryCount 不会跳变）
        EsSyncOutbox current = getById(id);
        if (current == null || !RetryStatus.PENDING.equals(current.getStatus())) {
            return -1;
        }
        int prevRetryCount = current.getRetryCount() == null ? 0 : current.getRetryCount();
        // 退避：30 * 2^retryCount，封顶 600（10 分钟）
        long backoffSeconds = Math.min(RETRY_BASE_SECONDS * (1L << prevRetryCount), RETRY_CAP_SECONDS);
        LocalDateTime nextRetryAt = nowMillis().plusSeconds(backoffSeconds);

        // 再写：retry_count+1、nextRetryAt 推到退避后、记录错误信息
        // 条件 status=PENDING 防止「已被手动标 SUCCESS 的行被这次旧事件覆盖」
        boolean updated = lambdaUpdate()
                .eq(EsSyncOutbox::getId, id)
                .eq(EsSyncOutbox::getStatus, RetryStatus.PENDING)
                .setSql("retry_count = retry_count + 1")
                .set(EsSyncOutbox::getNextRetryAt, nextRetryAt)
                .set(EsSyncOutbox::getErrorMsg, errorMsg)
                .update();
        if (!updated) {
            return -1;
        }
        return prevRetryCount + 1;
    }
}
