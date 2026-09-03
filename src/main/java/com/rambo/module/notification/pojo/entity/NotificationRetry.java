package com.rambo.module.notification.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.common.enumType.RetryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_notification_retry")
public class NotificationRetry implements Serializable {
    @Schema(description = "通知重试id")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "通知id")
    private Long messageId;

    @Schema(description = "通知内容")
    private String messageBody;

    @Schema(description = "状态 0-待重试 1-重试成功 2-重试失败终止")
    private RetryStatus status;

    @Schema(description = "重试次数")
    private Integer retryCount;

    @Schema(description = "错误信息")
    private String errorMessage;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
