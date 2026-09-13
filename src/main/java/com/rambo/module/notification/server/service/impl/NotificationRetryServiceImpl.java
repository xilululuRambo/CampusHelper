package com.rambo.module.notification.server.service.impl;

import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.enumType.RetryStatus;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.pojo.entity.NotificationRetry;
import com.rambo.module.notification.server.mapper.NotificationRetryMapper;
import com.rambo.module.notification.server.service.NotificationRetryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class NotificationRetryServiceImpl extends ServiceImpl<NotificationRetryMapper, NotificationRetry> implements NotificationRetryService {

    private static final Integer MAX_RETRY_COUNT = 5;

    /**
     * 通知发送失败时，写入重试表（实现自 {@link com.rambo.module.notification.server.service.NotificationRetryRecorder}）
     * @param msg 通知消息
     */
    @Override
    public void saveIfFail(NotificationMessage msg, String errorMessage) {
        // 幂等（双保险）：exists 预检为快速路径；并发原子性由 t_notification_retry.message_id 唯一索引保证，
        // 两个线程（生产者 nack 与消费者处理失败）同时写入时，后插入者抛 DuplicateKeyException 被捕获视为已存在。
        boolean exists = lambdaQuery()
                .eq(NotificationRetry::getMessageId, msg.getMessageId())
                .eq(NotificationRetry::getStatus, RetryStatus.PENDING)
                .exists();
        if (exists) {
            log.warn("该消息已在重试表中，跳过重复写入: messageId={}", msg.getMessageId());
            return;
        }
        NotificationRetry notificationRetry = new NotificationRetry();
        notificationRetry.setMessageId(msg.getMessageId());
        notificationRetry.setErrorMessage(errorMessage);
        notificationRetry.setMessageBody(JSONUtil.toJsonStr(msg));
        try {
            save(notificationRetry);
        } catch (DuplicateKeyException e) {
            // 并发窗口内对方已写入，幂等忽略
            log.warn("该消息已在重试表中（唯一索引兜底），跳过重复写入: messageId={}", msg.getMessageId());
        }
    }

    /**
     * 获取待重试的消息列表
     * @return 待重试的消息列表
     */
    @Override
    public List<NotificationRetry> getWaitRetryList() {
        return lambdaQuery().eq(NotificationRetry::getStatus, RetryStatus.PENDING).list();
    }

    /**
     * 重试次数+1，超过最大次标记失败
     * @param messageId   通知重试id
     * @param message 错误信息
     * @return true=本轮重试后已达上限被标记为 FAILED
     */
    @Override
    public boolean incrRetryCount(Long messageId, String message) {
        // 重试次数+1。status 条件既是幂等护栏也是语义护栏：只对「待重试」记录累加，
        // 已终态（SUCCESS/FAILED）的记录不会被后续迟到的失败回调改写
        lambdaUpdate()
                .eq(NotificationRetry::getMessageId, messageId)
                .eq(NotificationRetry::getStatus, RetryStatus.PENDING)
                .setSql("retry_count = retry_count + 1")
                .set(NotificationRetry::getErrorMessage, message)
                .update();
        // 超过最大重试次数，标记失败；返回是否刚被标记为 FAILED。
        // 必须带 status = PENDING 条件：让 PENDING -> FAILED 成为「一次性状态跃迁」，
        // 只有真正完成跃迁的那一次返回 true。原实现无 status 条件，
        // 后续任意次调用都会因 retry_count 仍 >= 上限而再次返回 true，导致同一条消息被反复投递死信。
        return lambdaUpdate()
                .eq(NotificationRetry::getMessageId, messageId)
                .eq(NotificationRetry::getStatus, RetryStatus.PENDING)
                .ge(NotificationRetry::getRetryCount, MAX_RETRY_COUNT)
                .set(NotificationRetry::getStatus, RetryStatus.FAILED)
                .update();
    }

    /**
     * 标记成功
     * @param messageId 通知id
     */
    @Override
    public void markSuccess(Long messageId) {
        // 只允许 PENDING -> SUCCESS：已 FAILED（已投死信、等待人工处理）的记录
        // 不得被迟到的 ack 回调复活为成功，否则死信队列与重试表状态互相矛盾
        lambdaUpdate()
                .eq(NotificationRetry::getMessageId, messageId)
                .eq(NotificationRetry::getStatus, RetryStatus.PENDING)
                .set(NotificationRetry::getStatus, RetryStatus.SUCCESS)
                .update();
    }
}
