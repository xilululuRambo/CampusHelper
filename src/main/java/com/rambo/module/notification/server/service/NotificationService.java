package com.rambo.module.notification.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.notification.pojo.vo.NotificationListVO;

public interface NotificationService extends IService<Notification> {
    /**
     * 根据消息ID查询是否存在通知
     * @param messageId 消息ID
     * @return 是否存在通知
     */
    boolean existsByMessageId(Long messageId);

    /**
     * 保存通知
     * @param msg 通知消息
     */
    void saveNotification(Notification msg);

    /**
     * 获取通知列表
     * @param notificationType 通知类型
     * @param pageQuery 分页查询参数
     * @return 通知列表分页结果集
     */
    PageResult<NotificationListVO> getList(NotificationType notificationType, PageQuery pageQuery);

    /**
     * 标记通知为已读
     * @param id 通知ID
     * @param userId 用户ID
     */
    void markRead(Long id, Long userId);

    /**
     * 标记所有通知为已读
     * @param userId 用户ID
     */
    void markAllRead(Long userId);

    /**
     * 统计用户未读通知数量
     * @param userId 用户ID
     * @return 未读数量
     */
    long countUnread(Long userId);
}
