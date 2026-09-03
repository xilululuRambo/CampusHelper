package com.rambo.notification;

import com.rambo.BaseApiTest;
import com.rambo.module.notification.enums.IsReadStatus;
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

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 通知模块补充测试：未读数、已读流转、类型过滤、越权标记。
 * 通知数据直接注入 Service 落库（绕过被 mock 的 MQ），仅验证接口行为与隔离性。
 */
class NotificationEdgeApiTest extends BaseApiTest {

    @Resource
    private NotificationService notificationService;

    private Notification newNotice(AuthUser user, NotificationType type, String content, Long refId) {
        Notification n = new Notification();
        n.setUserId(user.getUserId());
        n.setType(type);
        n.setContent(content);
        n.setRefId(refId);
        notificationService.saveNotification(n);
        return n;
    }

    private long unreadCount(AuthUser user) {
        ResponseEntity<Map> resp = get("/notification/unread-count", user);
        assertOk(resp);
        // long 被 JacksonConfig 统一序列化为 String
        return Long.parseLong(String.valueOf(resp.getBody().get("data")));
    }

    @Test
    @DisplayName("未读数：按用户隔离，A 的未读数不受 B 影响")
    void unreadCount_isolatedByUser() {
        AuthUser userA = newAuthedUser();
        AuthUser userB = newAuthedUser();
        long aBefore = unreadCount(userA);
        long bBefore = unreadCount(userB);

        newNotice(userA, NotificationType.GOODS_ORDER, "你有新的商品订单", 1L);
        newNotice(userB, NotificationType.TASK_NEW_APPLY, "你有新的任务申请", 2L);
        newNotice(userB, NotificationType.APPLY_AGREE, "申请被同意", 3L);

        assertThat(unreadCount(userA)).isEqualTo(aBefore + 1);
        assertThat(unreadCount(userB)).isEqualTo(bBefore + 2);
    }

    @Test
    @DisplayName("标记已读：单条通知置为已读，未读数同步减少")
    void markRead_single_ok() {
        AuthUser user = newAuthedUser();
        long before = unreadCount(user);
        Notification n = newNotice(user, NotificationType.TASK_COMPLETE, "任务完成", 10L);
        assertThat(unreadCount(user)).isEqualTo(before + 1);

        assertOk(put("/notification/" + n.getId() + "/read", null, user));

        // DB 状态已翻转
        Notification db = notificationService.getById(n.getId());
        assertThat(db.getIsRead()).isEqualTo(IsReadStatus.READ);
        assertThat(unreadCount(user)).isEqualTo(before);
    }

    @Test
    @DisplayName("标记已读：标记他人通知被拒（越权防护）")
    void markRead_othersNotice_rejected() {
        AuthUser userA = newAuthedUser();
        AuthUser userB = newAuthedUser();
        Notification n = newNotice(userA, NotificationType.APPLY_REJECT, "申请被拒绝", 20L);

        assertFailWithMsg(put("/notification/" + n.getId() + "/read", null, userB), "不存在");
    }

    @Test
    @DisplayName("标记已读：不存在的通知被拒")
    void markRead_notExists_rejected() {
        AuthUser user = newAuthedUser();
        assertFailWithMsg(put("/notification/987654321012/read", null, user), "不存在");
    }

    @Test
    @DisplayName("全部已读：只影响自己的通知，不影响他人未读数")
    void readAll_marksMineOnly() {
        AuthUser userA = newAuthedUser();
        AuthUser userB = newAuthedUser();
        long aBefore = unreadCount(userA);
        long bBefore = unreadCount(userB);
        newNotice(userA, NotificationType.TASK_DELIVER_CONFIRM, "交付待确认", 30L);
        newNotice(userA, NotificationType.GOODS_PAY, "商品已付款", 31L);
        newNotice(userB, NotificationType.TASK_EVALUATE, "收到评价", 32L);

        assertOk(put("/notification/read-all", null, userA));

        assertThat(unreadCount(userA)).isEqualTo(aBefore);
        assertThat(unreadCount(userB)).isEqualTo(bBefore + 1); // B 的未读不受影响
    }

    @Test
    @DisplayName("类型过滤：按通知类型过滤列表")
    void list_filterByType() {
        AuthUser user = newAuthedUser();
        newNotice(user, NotificationType.GOODS_ORDER, "商品下单通知", 40L);
        newNotice(user, NotificationType.APPLY_AGREE, "申请同意通知", 41L);

        // 枚举参数绑定：全局枚举转换器只认 @EnumValue 数字值（GOODS_ORDER=9，APPLY_AGREE=2）
        ResponseEntity<Map> resp = get("/notification?notificationType=" + NotificationType.GOODS_ORDER.getType()
                + "&pageNum=1&pageSize=100", user);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).isNotEmpty();
        // NotificationType @JsonValue 序列化为 type 数字
        assertThat(records).allMatch(r -> ((Number) r.get("type")).intValue() == 9);
        assertThat(records).noneMatch(r -> ((Number) r.get("type")).intValue() == 2);
    }
}
