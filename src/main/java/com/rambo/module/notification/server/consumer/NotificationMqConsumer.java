package com.rambo.module.notification.server.consumer;

import com.rabbitmq.client.Channel;
import com.rambo.infrastructure.messaging.RabbitmqConfig;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationProcessor;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 通知消息消费者（业务层）：接收 MQ 消息并回调 {@link NotificationProcessor}。
 * <p>
 * 消费端点放在业务层的原因：消费的是业务消息模型 {@link NotificationMessage}，
 * 幂等/落库/推送/重试表等业务细节全部由处理器承担；基础设施层只提供连接与协议转换，
 * 不感知任何业务消息类型，依赖方向保持「业务 → 基础设施」单向。
 */
@Component
@Slf4j
public class NotificationMqConsumer {

    @Resource
    private NotificationProcessor notificationProcessor;

    @RabbitListener(queues = RabbitmqConfig.NOTIFICATION_QUEUE)
    public void onMessage(Message amqpMessage,
                          NotificationMessage msg,
                          Channel channel) throws IOException {
        long deliveryTag = amqpMessage.getMessageProperties().getDeliveryTag();
        try {
            // 业务处理（幂等检查、落库；推送已异步化，失败由处理器写入重试表补偿）
            notificationProcessor.process(msg);
        } catch (Exception e) {
            // 防御性兜底：处理器内部已 catch 业务异常，此处仅兜底未知异常，避免消息无限重投
            log.error("通知消息处理异常（防御兜底）: messageId={}", msg == null ? null : msg.getMessageId(), e);
        } finally {
            // 确认移除：消息从主队列删除，补偿完全交由重试表；仅重试耗尽（FAILED）时才投递死信
            channel.basicAck(deliveryTag, false);
        }
    }
}
