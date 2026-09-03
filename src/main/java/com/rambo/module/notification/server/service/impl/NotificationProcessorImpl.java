package com.rambo.module.notification.server.service.impl;

import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationProcessor;
import com.rambo.infrastructure.websocket.WsMessenger;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.notification.server.service.NotificationRetryService;
import com.rambo.module.notification.server.service.NotificationService;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * 通知消息处理器（业务层实现，供消费者依赖倒置回调）：
 * 消费线程只做「幂等检查 → 落库」（毫秒级 DB 操作，失败写重试表补偿）；
 * WebSocket 推送为慢 I/O（STOMP relay 网络往返），落库成功后丢线程池异步执行，
 * 不阻塞消费线程 ack 与后续消息消费——任何一步慢不再拖垮整体吞吐。
 */
@Slf4j
@Component
public class NotificationProcessorImpl implements NotificationProcessor {

    /** WebSocket 用户通知目的地 */
    private static final String USER_NOTIFICATION_DESTINATION = "/queue/notifications";

    @Resource
    private NotificationService notificationService;

    /** WebSocket 推送门面：内部对 SimpMessagingTemplate 懒加载注入，避免触发 WebSocket 配置形成循环依赖（同 NotificationSenderImpl） */
    @Resource
    private WsMessenger wsMessenger;

    @Resource
    private NotificationRetryService notificationRetryService;

    /**
     * 统一异步线程池（AsyncConfig 的 taskExecutor，带 MDC 装饰可透传日志链路）：
     * 推送异步化专用，消费线程快速 ack 后由该池完成慢 I/O
     */
    @Resource(name = "taskExecutor")
    private Executor taskExecutor;

    @Override
    public void process(NotificationMessage msg) {
        // 1. 幂等落库（双保险）：
        //    预检 exists 作为快速路径；真正的并发原子性由 t_notification.message_id 唯一索引保证——
        //    两个线程同时消费同一条消息时，先插入者成功、后插入者抛 DuplicateKeyException 被捕获视为已处理。
        //    该模式在唯一索引缺失时行为与旧版等价（不回归），索引存在时彻底消除 check-then-insert 竞态。
        if (notificationService.existsByMessageId(msg.getMessageId())) {
            log.warn("重复消息，已忽略: messageId={}", msg.getMessageId());
            return;
        }
        Notification notification = toEntity(msg);
        try {
            notificationService.saveNotification(notification);
        } catch (DuplicateKeyException e) {
            // 并发窗口内对方已落库：幂等忽略
            log.warn("重复消息（唯一索引兜底），已忽略: messageId={}", msg.getMessageId());
            return;
        } catch (Exception e) {
            // 落库失败：写入重试表，由定时任务重新投递，消费端幂等兜底
            log.error("通知落库失败: messageId={}", msg.getMessageId(), e);
            notificationRetryService.saveIfFail(msg, "消费者落库失败: " + e.getMessage());
            return;
        }

        // 2. WebSocket 推送：异步执行，与落库解耦（推送慢不阻塞 ack 与后续消息消费）
        //    失败静默（与 NotificationSenderImpl.sendSync 同语义）：站内信已落库，
        //    用户主动拉取列表必然可见，WS 仅是实时增强，不为此触发整条消息重投
        CompletableFuture.runAsync(() -> {
            try {
                wsMessenger.sendToUser(msg.getUserId(), USER_NOTIFICATION_DESTINATION, notification);
            } catch (Exception e) {
                log.warn("WebSocket 推送通知失败（站内信已落库，不影响）: messageId={}, userId={}",
                        msg.getMessageId(), msg.getUserId(), e);
            }
        }, taskExecutor);
    }

    /**
     * 协议对象转持久化实体（createTime/updateTime 由 MyMetaObjectHandler 自动填充）
     */
    private Notification toEntity(NotificationMessage msg) {
        Notification notification = new Notification();
        notification.setMessageId(msg.getMessageId());
        notification.setUserId(msg.getUserId());
        notification.setType(msg.getType());
        notification.setContent(msg.getContent());
        notification.setRefId(msg.getRefId());
        return notification;
    }
}
