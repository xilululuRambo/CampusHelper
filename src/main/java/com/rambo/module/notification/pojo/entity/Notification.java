package com.rambo.module.notification.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.module.notification.enums.NotificationType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_notification")
public class Notification implements Serializable {

    @Schema(description = "通知ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "消息ID")
    private Long messageId;

    @Schema(description = "接收通知的用户ID")
    private Long userId;

    @Schema(description = "通知类型：1-任务新申请，2-申请被同意，3-申请被拒绝，4-任务交付待确认，5-任务确认完成，" +
            "6-任务完成，7-收到任务评价，8-商品被下单，9-商品已付款，10-商品已发货，11-交易完成，12-收到商品评价，" +
            "13-积分调整通知，14-信誉分调整通知")
    private NotificationType type;

    @Schema(description = "通知内容")
    private String content;

    @Schema(description = "是否已读：0-未读，1-已读")
    private IsReadStatus isRead;

    @Schema(description = "关联业务ID（商品订单ID）")
    private Long refId;

    @Schema(description = "通知发送时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}
