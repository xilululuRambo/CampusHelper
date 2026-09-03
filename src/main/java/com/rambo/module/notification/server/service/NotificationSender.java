package com.rambo.module.notification.server.service;

import com.rambo.module.notification.pojo.dto.NotificationMessage;

/**
 * 通知发送门面：业务模块发通知的唯一入口（依赖倒置，解耦消息实现细节）
 *
 * <p>两种发送模式：
 * <ul>
 *   <li>{@link #sendSync}：同步落库 + WebSocket 推送。与业务同一事务，成功必达、回滚必无，
 *       适用于低频管理操作（积分/信誉分调整等），无重试负担；推送失败静默不影响站内信。</li>
 *   <li>{@link #sendAsync}：事务提交后走 MQ 异步链路（含重试表补偿/死信），
 *       适用于高频业务事件（任务/商品通知），可靠性由现有三层重试机制保证。</li>
 * </ul>
 */
public interface NotificationSender {

    /**
     * 同步发送：与业务同事务落库（站内信必达），并尝试 WebSocket 实时推送（失败静默）
     *
     * @param message 通知消息
     */
    void sendSync(NotificationMessage message);

    /**
     * 异步发送：事务提交后经 MQ 投递（消费者落库 + WebSocket 推送），失败走重试表补偿
     *
     * @param message 通知消息
     */
    void sendAsync(NotificationMessage message);
}
