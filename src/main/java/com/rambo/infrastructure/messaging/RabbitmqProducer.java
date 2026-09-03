package com.rambo.infrastructure.messaging;

import com.rambo.infrastructure.database.TransactionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;

/**
 * 通用 RabbitMQ 生产者（基础设施层）：
 * <p>
 * 只提供「事务提交后发送 + 发布确认回调」的通用能力，不感知任何业务消息类型——
 * 消息体由调用方（业务层）自行构造，确认回调由调用方注入。
 * 依赖方向严格保持「业务 → 基础设施」单向，基础设施不再反向依赖业务契约。
 */
@Component
@Slf4j
public class RabbitmqProducer {

    /** 发布确认等待超时时间：超时按失败处理（防网络挂起导致 future 永不完成、回调不触发） */
    private static final long CONFIRM_TIMEOUT_MS = 5_000L;

    @Resource
    private RabbitTemplate rabbitTemplate;

    /**
     * 发送消息：若当前处于数据库事务中，实际发送延后到事务提交后（afterCommit）执行，
     * 事务回滚时不发送，避免「假消息」；无事务上下文时立即发送。
     *
     * @param exchange        交换机
     * @param routingKey      路由键
     * @param payload         消息体（Jackson JSON 序列化）
     * @param messageId       消息ID（幂等/重试关联依据；可为 null）
     * @param confirmCallback 发布确认回调 (ack, reason)，可为 null（如只关心发送不关心结果）
     */
    public void sendAfterCommit(String exchange, String routingKey, Object payload, Long messageId,
                                BiConsumer<Boolean, String> confirmCallback) {
        TransactionUtils.afterCommit(() -> doSend(exchange, routingKey, payload, messageId, confirmCallback));
    }

    /**
     * 发送到死信队列（作为人工处理入口）
     *
     * @param exchange   死信交换机
     * @param routingKey 死信路由键
     * @param payload    消息体
     * @param messageId  消息ID（可为 null）
     */
    public void sendToDeadLetter(String exchange, String routingKey, Object payload, Long messageId) {
        doSend(exchange, routingKey, payload, messageId, null);
    }

    /**
     * 实际发送逻辑（事务提交后由 {@link #sendAfterCommit} 触发）
     */
    private void doSend(String exchange, String routingKey, Object payload, Long messageId,
                        BiConsumer<Boolean, String> confirmCallback) {
        log.info("发送MQ消息: exchange={}, routingKey={}, messageId={}", exchange, routingKey, messageId);

        // 消息后处理器：持久化 + messageId（幂等/重试关联依据）
        MessagePostProcessor postProcessor = message -> {
            MessageProperties props = message.getMessageProperties();
            props.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
            if (messageId != null) {
                props.setMessageId(String.valueOf(messageId));
            }
            return message;
        };

        // 发布确认回调（仅当调用方关心结果时注册）
        CorrelationData correlationData = messageId != null ? new CorrelationData(String.valueOf(messageId)) : null;
        if (correlationData != null && confirmCallback != null) {
            correlationData.getFuture()
                    // 超时兜底：确认迟迟未回（如网络挂起）时以 TimeoutException 异常完成，走失败回调；
                    // 若消息实际已送达，由消费端幂等（existsByMessageId）保证不重复处理
                    .orTimeout(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .thenAccept(confirm -> confirmCallback.accept(confirm.isAck(), confirm.getReason()))
                    .exceptionally(ex -> {
                        // 结果不确定（超时/网络异常导致 future 异常完成），按失败回调，防止消息静默丢失
                        String reason = ex instanceof TimeoutException
                                ? "发送确认超时(" + CONFIRM_TIMEOUT_MS / 1000 + "s)"
                                : "发送确认异常: " + ex.getMessage();
                        confirmCallback.accept(false, reason);
                        return null;
                    });
        }

        rabbitTemplate.convertAndSend(exchange, routingKey, payload, postProcessor, correlationData);
    }
}
