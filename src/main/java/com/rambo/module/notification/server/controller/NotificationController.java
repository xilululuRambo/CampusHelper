package com.rambo.module.notification.server.controller;

import com.rambo.module.notification.enums.NotificationType;
import com.rambo.common.context.IdHolder;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.notification.pojo.vo.NotificationListVO;
import com.rambo.module.notification.server.service.NotificationService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/notification")
@Slf4j
@Validated
@Tag(name = "通知接口")
public class NotificationController {
    @Resource
    private NotificationService notificationService;

    /**
     * 获取通知列表
     * @param notificationType 通知类型
     * @param pageQuery 分页查询参数
     * @return 通知列表分页结果集
     */
    @GetMapping
    @Operation(summary = "获取通知列表")
    public Result<PageResult<NotificationListVO>> getNotificationList(NotificationType notificationType,PageQuery pageQuery) {
        log.info("获取通知列表");
        PageResult<NotificationListVO> list = notificationService.getList(notificationType,pageQuery);
        return Result.success(list);
    }

    /**
     * 标记通知为已读
     * @param id 通知ID
     * @return 成功结果
     */
    @PutMapping("/{id}/read")
    public Result<Void> read(@PathVariable Long id) {
        notificationService.markRead(id, IdHolder.getId());
        return Result.success();
    }

    /**
     * 标记所有通知为已读
     * @return 成功结果
     */
    @PutMapping("/read-all")
    public Result<Void> readAll() {
        notificationService.markAllRead(IdHolder.getId());
        return Result.success();
    }

    /**
     * 获取未读通知数量（前端红点角标）
     * @return 未读数量
     */
    @GetMapping("/unread-count")
    @Operation(summary = "获取未读通知数量")
    public Result<Long> getUnreadCount() {
        return Result.success(notificationService.countUnread(IdHolder.getId()));
    }
}
