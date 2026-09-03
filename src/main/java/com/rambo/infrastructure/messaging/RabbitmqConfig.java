package com.rambo.infrastructure.messaging;

import org.springframework.amqp.core.*;
import org.springframework.context.annotation.Bean;
import org.springframework.stereotype.Component;

@Component
public class RabbitmqConfig {
    // 通知交换机
    public static final String NOTIFICATION_EXCHANGE = "notification.exchange";
    //路由键
    public static final String NOTIFICATION_ROUTING_KEY = "notification.routing_key";
    //队列
    public static final String NOTIFICATION_QUEUE = "notification.queue";



    //死信交换机
    public static final String NOTIFICATION_DEAD_EXCHANGE = "notification.dead.exchange";
    // 死信队列
    public static final String NOTIFICATION_DEAD_QUEUE = "notification.dead.queue";
    // 死信路由键
    public static final String NOTIFICATION_DEAD_ROUTING_KEY = "notification.dead.routing_key";

    // 通知队列消息过期时间：24 小时（超时未消费进入死信队列）；QueueBuilder.ttl(int) 上限约 24.8 天，24h 在 int 范围内
    private static final int NOTIFICATION_QUEUE_TTL_MS = 24 * 60 * 60 * 1000;
    // 死信队列消息过期时间：7 天（死信消息也无人消费则彻底丢弃）
    private static final int DEAD_QUEUE_TTL_MS = 7 * 24 * 60 * 60 * 1000;
    // 通知队列最大消息条数
    private static final long NOTIFICATION_QUEUE_MAX_LENGTH = 50000L;

    // 通知交换机
    @Bean
    public TopicExchange notificationExchange() {
        return ExchangeBuilder
                .topicExchange(NOTIFICATION_EXCHANGE)
                .durable(true)
                .build();
    }

    // 通知队列
    @Bean
    public Queue notificationQueue() {
        return QueueBuilder
                .durable(NOTIFICATION_QUEUE)
                .deadLetterExchange(NOTIFICATION_DEAD_EXCHANGE)
                .deadLetterRoutingKey(NOTIFICATION_DEAD_ROUTING_KEY)
                // 过期时间24小时
                .ttl(NOTIFICATION_QUEUE_TTL_MS)
                .maxLength(NOTIFICATION_QUEUE_MAX_LENGTH)
                .build();
    }

    //绑定队列到交换机
    @Bean
    public Binding notificationBinding(Exchange notificationExchange, Queue notificationQueue) {
        return BindingBuilder
                .bind(notificationQueue)
                .to(notificationExchange)
                .with(NOTIFICATION_ROUTING_KEY)
                .noargs();
    }

    // 死信交换机
    @Bean
    public TopicExchange notificationDeadExchange() {
        return ExchangeBuilder
                .topicExchange(NOTIFICATION_DEAD_EXCHANGE)
                .durable(true)
                .build();
    }

    // 死信队列
    @Bean
    public Queue notificationDeadQueue() {
        return QueueBuilder
                .durable(NOTIFICATION_DEAD_QUEUE)
                // 过期时间7天
                .ttl(DEAD_QUEUE_TTL_MS)
                .build();
    }

    // 死信绑定
    @Bean
    public Binding notificationDeadBinding(Exchange notificationDeadExchange, Queue notificationDeadQueue) {
        return BindingBuilder
                .bind(notificationDeadQueue)
                .to(notificationDeadExchange)
                .with(NOTIFICATION_DEAD_ROUTING_KEY)
                .noargs();
    }
}
