package com.rambo.infrastructure.search;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.common.enumType.EsSyncOp;
import com.rambo.common.enumType.RetryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * ES 同步发件箱（事务性 Outbox）。
 *
 * <p>业务写库与「同步到 ES 的意图」在同一数据库事务中落库，保证原子性：
 * 要么业务数据与同步意图一起提交，要么都回滚。这样即使进程在事务提交后立即崩溃，
 * 只要事务已提交，待同步意图就已持久化，可由 XXL-JOB 兜底补偿，不再依赖内存中的异步任务。</p>
 *
 * <p><b>每条 enqueue 都是独立的一行</b>，行 id 直接作为 ES external version：
 * 自增 id 在单库内严格单调递增，满足 ES 「新版本必须严格大于当前版本」的要求；
 * 多次连续 enqueue 会留下多行 PENDING，按 id ASC 顺序被派发，后到的写入天然版本更高，
 * 旧事件会被 ES 以 409 拒绝（旧版本号更小），避免「旧数据覆盖新数据」的乱序问题。</p>
 *
 * <p><b>重试按指数退避</b>：派发失败时由 {@code EsSyncOutboxService.incrRetryCount}
 * 把 {@link #nextRetryAt} 推进为 {@code NOW + min(30 * 2^retryCount, 600) 秒}（封顶 10 分钟）；
 * {@code getWaitList} 通过 {@code next_retry_at <= NOW} 过滤冷却期内的行，避免雪崩式重投。</p>
 */
@Data
@TableName("t_es_sync_outbox")
public class EsSyncOutbox {

    @Schema(description = "主键，同时充当 ES 外部版本号（自增 id 严格单调）")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "业务数据ID（商品ID/任务ID）")
    private Long dataId;

    @Schema(description = "数据类型 goods商品 task任务")
    private String dataType;

    @Schema(description = "操作类型 0-UPSERT 1-DELETE")
    private EsSyncOp opType;

    @Schema(description = "待清理的 OSS 文件（objectName），逗号分隔；ES 同步达成后删除并清空")
    private String fileUrls;

    @Schema(description = "已重试次数")
    private Integer retryCount;

    @Schema(description = "状态 0-待同步 1-同步成功 2-重试失败终止")
    private RetryStatus status;

    @Schema(description = "错误信息")
    private String errorMsg;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    /**
     * 下次可重试时间（指数退避）。
     * <p>派发失败后由 {@code incrRetryCount} 推后为 {@code NOW + min(30*2^retryCount, 600)} 秒；
     * 冷却期内 {@code getWaitList} 不会取到该行，避免雪崩式重投。
     * 默认 {@code CURRENT_TIMESTAMP}，新登记的行立即可派发。</p>
     */
    @Schema(description = "下次可重试时间（指数退避：失败后 = NOW + min(30*2^retryCount, 600) 秒）")
    private LocalDateTime nextRetryAt;
}
