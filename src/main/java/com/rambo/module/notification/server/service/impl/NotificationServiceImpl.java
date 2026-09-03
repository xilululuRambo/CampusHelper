package com.rambo.module.notification.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.MessageConstants;
import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.common.context.IdHolder;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.notification.pojo.vo.NotificationListVO;
import com.rambo.module.notification.server.mapper.NotificationMapper;
import com.rambo.module.notification.server.service.NotificationService;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;

@Service
public class NotificationServiceImpl extends ServiceImpl<NotificationMapper, Notification> implements NotificationService {


    /**
     * 根据消息ID查询是否存在通知
     * @param messageId 消息ID
     * @return 是否存在通知
     */
    @Override
    public boolean existsByMessageId(Long messageId) {
        return lambdaQuery().eq(Notification::getMessageId, messageId).exists();
    }

    /**
     * 保存通知
     * @param msg 通知消息
     */
    @Override
    public void saveNotification(Notification msg) {
        save(msg);
    }

    /**
     * 获取通知列表
     * @param notificationType 通知类型
     * @param pageQuery 分页查询参数
     * @return 通知列表分页结果集
     */
    @Override
    public PageResult<NotificationListVO> getList(NotificationType notificationType, PageQuery pageQuery) {
        //获取userid
        Long userId = IdHolder.getId();

        // 分页查询
        Page<Notification> page = new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize());

        // 执行查询
        Page<Notification> pageList = lambdaQuery()
                .eq(notificationType != null, Notification::getType, notificationType)
                .eq(Notification::getUserId, userId)
                .orderByDesc(Notification::getCreateTime)
                .page(page);

        // 处理空结果
        if (pageList.getTotal() == 0) {
            return new PageResult<>(0L, Collections.emptyList());
        }

        // 转换为VO
        List<NotificationListVO> itemList = pageList.getRecords().stream().map(
                notification -> {
                    return BeanUtil.copyProperties(notification, NotificationListVO.class);
                }
        ).toList();

        return new PageResult<>(pageList.getTotal(), itemList);
    }

    /**
     * 标记通知为已读
     * @param id 通知ID
     * @param userId 用户ID
     */
    @Override
    public void markRead(Long id, Long userId) {
        boolean exists = lambdaQuery()
                .eq(Notification::getId, id)
                .eq(Notification::getUserId, userId)
                .exists();
        if (!exists) {
            throw new BusinessException(MessageConstants.NOTIFICATION_NOT_FOUND);
        }
        // 标记为已读
        lambdaUpdate()
                .eq(Notification::getId, id)
                .set(Notification::getIsRead, IsReadStatus.READ)
                .update();
    }

    /**
     * 标记所有通知为已读
     * @param userId 用户ID
     */
    @Override
    public void markAllRead(Long userId) {
        lambdaUpdate()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, IsReadStatus.UNREAD)
                .set(Notification::getIsRead, IsReadStatus.READ)
                .update();
    }

    /**
     * 统计用户未读通知数量
     * @param userId 用户ID
     * @return 未读数量
     */
    @Override
    public long countUnread(Long userId) {
        return lambdaQuery()
                .eq(Notification::getUserId, userId)
                .eq(Notification::getIsRead, IsReadStatus.UNREAD)
                .count();
    }
}
