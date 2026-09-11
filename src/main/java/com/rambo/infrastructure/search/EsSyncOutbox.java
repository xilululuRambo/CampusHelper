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
 * <p>同一 {@code dataType + dataId} 只保留一行（唯一索引），后到的意图覆盖先到的：
 * 由于派发时会回源读取最新实体，多个 UPSERT 合并为一行是安全的；DELETE 会覆盖 UPSERT。</p>
 */
@Data
@TableName("t_es_sync_outbox")
public class EsSyncOutbox {

    @Schema(description = "主键")
    @TableId(type = IdType.AUTO)
    private Long id;

    @Schema(description = "业务数据ID（商品ID/任务ID）")
    private Long dataId;

    @Schema(description = "数据类型 goods商品 task任务")
    private String dataType;

    @Schema(description = "操作类型 0-UPSERT 1-DELETE")
    private EsSyncOp opType;

    /**
     * ES 外部版本号（epoch 毫秒，进程内严格单调）。
     * <p>列名用 es_version 以避开 MySQL 的 version() 函数名歧义，
     * 同时避免被 MyBatis-Plus 乐观锁插件误识别。</p>
     */
    @Schema(description = "ES外部版本号（严格单调，防乱序）", hidden = true)
    @TableField("es_version")
    private Long esVersion;

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
}
