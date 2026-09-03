package com.rambo.notification;

import com.rambo.BaseApiTest;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.helper.AuthUser;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.notification.server.service.NotificationService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知接口测试 —— 含 P0 越权回归用例。
 *
 * 【缺陷回归】NotificationServiceImpl.getList 未按 userId 过滤，
 * 任何登录用户都能看到全部用户的通知。该用例当前会失败（红），修复后转绿。
 * 通知数据直接注入 Service 落库（绕过被 mock 的 MQ），仅验证列表隔离。
 */
class NotificationApiTest extends BaseApiTest {

    @Resource
    private NotificationService notificationService;

    @Test
    @DisplayName("回归：A 用户的通知列表不应包含 B 用户的通知（当前未按用户隔离 → 红）")
    void notificationList_isolatedByUser() {
        // 1. 造两个用户
        AuthUser userA = newAuthedUser();
        AuthUser userB = newAuthedUser();

        // 2. 直接给 B 落一条通知（消息走 Service，不经 MQ）
        Notification toB = new Notification();
        toB.setUserId(userB.getUserId());
        toB.setType(NotificationType.GOODS_ORDER);
        toB.setContent("你有新的商品订单");
        toB.setRefId(10001L);
        notificationService.saveNotification(toB);
        Long bNoticeId = toB.getId();

        // 3. A 查询自己的通知列表
        ResponseEntity<Map> resp = get("/notification?pageNum=1&pageSize=1000", userA);
        assertOk(resp);

        // 4. 断言：A 的列表里绝不能出现 B 的通知
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        boolean leaked = records.stream()
                .anyMatch(r -> bNoticeId.toString().equals(String.valueOf(r.get("id"))));
        assertThat(leaked)
                .as("越权缺陷：A 用户看到了 B 用户的通知 %s", bNoticeId)
                .isFalse();
    }

    @Test
    @DisplayName("通知列表：本人通知可正常查询")
    void notificationList_ownNoticeVisible() {
        AuthUser user = newAuthedUser();
        Notification notice = new Notification();
        notice.setUserId(user.getUserId());
        notice.setType(NotificationType.APPLY_AGREE);
        notice.setContent("你的任务申请已被同意");
        notice.setRefId(10002L);
        notificationService.saveNotification(notice);

        ResponseEntity<Map> resp = get("/notification?pageNum=1&pageSize=1000", user);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        // NotificationListVO 不含 id，按 refId + content 匹配
        assertThat(records).anyMatch(r ->
                "10002".equals(String.valueOf(r.get("refId")))
                        && "你的任务申请已被同意".equals(r.get("content")));
    }
}
