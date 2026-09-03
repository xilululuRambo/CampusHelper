package com.rambo.module.notification.pojo.dto;

import com.rambo.module.notification.enums.NotificationType;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 通知消息（MQ 流转与业务调用的统一协议对象，与持久化实体解耦）
 * 业务模块与基础设施层（生产者/消费者）都只依赖本 DTO，
 * 不直接接触 Notification 实体 / 重试表等实现细节。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NotificationMessage implements Serializable {

    /** 全局消息ID（发送方生成，消费者幂等去重依据） */
    private Long messageId;

    /** 接收通知的用户ID */
    private Long userId;

    /** 通知类型 */
    private NotificationType type;

    /** 通知内容 */
    private String content;

    /** 关联业务ID（任务ID/订单ID等），前端跳转用 */
    private Long refId;
}
