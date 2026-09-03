package com.rambo.module.notification.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.notification.pojo.entity.NotificationRetry;

import java.util.List;

/**
 * 通知重试表服务：实现重试记录契约 {@link NotificationRetryRecorder}（业务内部能力），
 * 查询类能力（获取待重试列表）仅业务层/定时任务使用
 */
public interface NotificationRetryService extends IService<NotificationRetry>, NotificationRetryRecorder {

    /**
     * 获取待重试的消息列表
     * @return 待重试的消息列表
     */
    List<NotificationRetry> getWaitRetryList();
}
