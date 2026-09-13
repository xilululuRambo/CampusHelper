package com.rambo.module.notification.server.job;

import cn.hutool.json.JSONUtil;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.cache.LockClient;
import com.rambo.infrastructure.messaging.RabbitmqConfig;
import com.rambo.infrastructure.messaging.RabbitmqProducer;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.pojo.entity.NotificationRetry;
import com.rambo.module.notification.server.service.NotificationRetryService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * XXL-JOB：重试通知发送失败的消息（可靠性补偿闭环的定时重投入口）
 *
 * <p>重试表存的是协议对象 {@link NotificationMessage} 的 JSON，反序列化后通过
 * {@link RabbitmqProducer#sendAfterCommit} 重投，发布确认回调由本 Job 注入：
 * ack → 标记成功；nack → 重试次数+1，超过上限投死信（人工处理入口）。</p>
 */
@Slf4j
@Component
public class NotificationRetryJob {

    @Resource
    private NotificationRetryService notificationRetryService;
    @Resource
    private RabbitmqProducer rabbitmqProducer;
    @Resource
    private LockClient lockClient;

    @XxlJob("notificationRetryJob")
    public void execute() {
        // 分布式锁：重投不是「纯读」操作——每投一条都会在发布确认回调里改写 retry_count/status。
        // 两个执行器并发跑（手动重试、调度重叠）会把同一批消息各投一遍，确认回调成对触发，
        // retry_count 一次 +2，5 次上限实际只够 2~3 轮，消息被提前投进死信。
        // 抢不到锁说明已有实例在处理，本次直接跳过（与 GoodsOrderTimeoutJob 同构）。
        boolean locked = lockClient.tryLock(PrefixConstants.NOTIFICATION_RETRY_LOCK, 0, TimeUnit.SECONDS);
        if (!locked) {
            XxlJobHelper.log("通知重投任务正在其他执行器运行，本次跳过");
            return;
        }
        try {
            doExecute();
        } finally {
            lockClient.unlock(PrefixConstants.NOTIFICATION_RETRY_LOCK);
        }
    }

    private void doExecute() {
        // 1. 查询待重试通知
        List<NotificationRetry> list = notificationRetryService.getWaitRetryList();
        if (list.isEmpty()) {
            return;
        }

        // 2. 遍历待重试通知，重试发送（重试表存的是协议对象 NotificationMessage JSON）
        for (NotificationRetry retry : list) {
            NotificationMessage msg = JSONUtil.toBean(retry.getMessageBody(), NotificationMessage.class);
            rabbitmqProducer.sendAfterCommit(
                    RabbitmqConfig.NOTIFICATION_EXCHANGE, RabbitmqConfig.NOTIFICATION_ROUTING_KEY,
                    msg, msg.getMessageId(),
                    (ack, reason) -> {
                        if (ack) {
                            // 发送成功：更新重试状态为成功
                            notificationRetryService.markSuccess(msg.getMessageId());
                        } else {
                            log.error("重试发送失败: messageId={}, reason={}", msg.getMessageId(), reason);
                            // 重试次数+1，超过最大次数标记 FAILED 并投递死信
                            boolean failed = notificationRetryService.incrRetryCount(msg.getMessageId(), reason);
                            if (failed) {
                                log.warn("重试已达上限，消息进入死信: messageId={}", msg.getMessageId());
                                rabbitmqProducer.sendToDeadLetter(
                                        RabbitmqConfig.NOTIFICATION_DEAD_EXCHANGE,
                                        RabbitmqConfig.NOTIFICATION_DEAD_ROUTING_KEY, msg, msg.getMessageId());
                            }
                        }
                    });
        }

        XxlJobHelper.handleSuccess("处理完成，共重投 " + list.size() + " 条通知");
    }
}
