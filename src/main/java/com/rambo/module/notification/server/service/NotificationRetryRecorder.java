package com.rambo.module.notification.server.service;

import com.rambo.module.notification.pojo.dto.NotificationMessage;

/**
 * 通知重试记录器：由业务层实现（module.notification，基于重试表），
 * 生产者确认回调只依赖本接口，不接触重试表实体/服务（依赖倒置）
 */
public interface NotificationRetryRecorder {

    /**
     * 发送失败时写入重试记录（幂等：同 messageId 已有待重试记录则不重复写入）
     *
     * @param message 通知消息
     * @param reason  失败原因
     */
    void saveIfFail(NotificationMessage message, String reason);

    /**
     * 重试次数+1，超过最大次数标记失败
     *
     * @param messageId 通知消息ID
     * @param message   错误信息
     * @return true=本轮重试后已达上限被标记为 FAILED
     */
    boolean incrRetryCount(Long messageId, String message);

    /**
     * 标记重试成功
     *
     * @param messageId 通知消息ID
     */
    void markSuccess(Long messageId);
}
