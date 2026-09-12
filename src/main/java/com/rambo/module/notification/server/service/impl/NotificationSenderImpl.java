package com.rambo.module.notification.server.service.impl;

import com.rambo.infrastructure.messaging.IdWorker;
import com.rambo.infrastructure.messaging.RabbitmqConfig;
import com.rambo.infrastructure.messaging.RabbitmqProducer;
import com.rambo.infrastructure.websocket.WsMessenger;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.notification.server.service.NotificationRetryRecorder;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.module.notification.server.service.NotificationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 通知发送门面实现（业务层）：编排「落库 + MQ 投递 + WebSocket 推送」，
 * 对外仅暴露轻量协议对象 NotificationMessage，业务模块不感知 MQ/实体细节。
 *
 * <p>位于业务层的原因：本实现需要操作 Notification 实体与 NotificationService（落库），
 * 属于通知领域的业务编排；基础设施 messaging 包只保留接口定义与纯 MQ 传输，
 * 依赖方向严格保持「业务 → 基础设施」单向，基础设施不再反向依赖业务。
 */
@Slf4j
@Component
public class NotificationSenderImpl implements NotificationSender {

    /** WebSocket 用户通知目的地（与 RabbitmqConsumer 推送保持一致） */
    private static final String USER_NOTIFICATION_DESTINATION = "/queue/notifications";

    @Resource
    private NotificationService notificationService;

    /**
     * WebSocket 推送门面：内部对 SimpMessagingTemplate 懒加载注入，避免触发 WebSocket 配置
     * （webSocketConfig → chatAuthInterceptor → 业务 Service）形成循环依赖；推送异常原样透出，
     * 由本方法按「站内信已落库、推送失败静默」语义兜底
     */
    @Resource
    private WsMessenger wsMessenger;

    @Resource
    private RabbitmqProducer rabbitmqProducer;

    @Resource
    private IdWorker idWorker;

    /** 重试记录契约：生产侧发送失败（nack/确认超时）时写重试表，由 XXL-JOB 定时任务重投 */
    @Resource
    private NotificationRetryRecorder notificationRetryRecorder;

    @Override
    public void sendSync(NotificationMessage message) {
        Notification notification = toEntity(message);
        // 1. 同步落库：与业务同事务，业务回滚则通知不存在（站内信必达，无需重试补偿）
        notificationService.saveNotification(notification);
        // 2. WebSocket 实时推送（增强能力）：失败静默——站内信已落库，推送丢失不影响用户感知
        try {
            wsMessenger.sendToUser(message.getUserId(), USER_NOTIFICATION_DESTINATION, notification);
        } catch (Exception e) {
            log.warn("WebSocket 推送通知失败（站内信已落库，不影响）: userId={}, type={}",
                    message.getUserId(), message.getType(), e);
        }
    }

    @Override
    public void sendAsync(NotificationMessage message) {
        // 委托 MQ 链路：事务提交后发送，消费者幂等落库 + 推送，失败走重试表补偿；
        // messageId 由本方法生成（MQ 幂等/重试关联依据）；发送失败由确认回调写入重试表
        if (message.getMessageId() == null) {
            message.setMessageId(idWorker.nextId());
        }
        rabbitmqProducer.sendAfterCommit(
                RabbitmqConfig.NOTIFICATION_EXCHANGE, RabbitmqConfig.NOTIFICATION_ROUTING_KEY,
                message, message.getMessageId(),
                (ack, reason) -> {
                    // 生产侧失败（nack/确认超时，结果不确定时按失败处理）：消息可能未入队、消费端
                    // 永远收不到，必须由发送方写重试表，由 XXL-JOB 重投；若消息实际已送达，消费端幂等兜底去重
                    if (!ack) {
                        log.error("通知发送失败，写入重试表待重投: messageId={}, reason={}",
                                message.getMessageId(), reason);
                        notificationRetryRecorder.saveIfFail(message, "发送失败: " + reason);
                    }
                });
    }

    /**
     * 消息 DTO 转持久化实体（仅同步落库场景需要）；messageId 用全局唯一 ID（幂等/重试表复用）
     */
    private Notification toEntity(NotificationMessage message) {
        Notification notification = new Notification();
        notification.setMessageId(idWorker.nextId());
        notification.setUserId(message.getUserId());
        notification.setType(message.getType());
        notification.setContent(message.getContent());
        notification.setRefId(message.getRefId());
        return notification;
    }
}
