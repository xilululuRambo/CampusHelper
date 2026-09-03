package com.rambo.module.notification.server.service;

import com.rambo.module.notification.pojo.dto.NotificationMessage;

/**
 * 通知消息处理器：由业务层实现（module.notification），
 * 消费者只负责接收消息并回调本接口，不接触任何业务实现细节（依赖倒置）
 */
public interface NotificationProcessor {

    /**
     * 处理通知消息（业务实现负责：幂等检查、落库；落库失败自行写重试表，
     * 推送异步执行且失败静默——站内信已落库）
     *
     * @param message 通知消息
     */
    void process(NotificationMessage message);
}
