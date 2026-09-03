package com.rambo.chat;

import com.rambo.BaseApiTest;
import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.module.chat.enums.MessageType;
import com.rambo.helper.AuthUser;
import com.rambo.module.chat.pojo.entity.ChatMessage;
import com.rambo.module.chat.server.service.ChatService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFail;
import static com.rambo.helper.AssertHelper.assertOk;

/**
 * 聊天接口测试 —— 含 P0 越权回归用例。
 *
 * 【缺陷回归】ChatHistoryController.getMessages 仅凭 sessionId 查询，
 * 无任何会话归属校验。sessionId 格式可枚举（task_{id}/goods_{id}），
 * 任何登录用户可读取任意会话消息。该用例当前红，修复后绿。
 * 预期修复方式：校验当前用户是否为该会话参与人（复用 ChatAuthInterceptor 逻辑），
 * 非参与人抛 BusinessException(NO_PERMISSION)。
 */
class ChatApiTest extends BaseApiTest {

    @Resource
    private ChatService chatService;

    /** 造一条真实商品会话：卖家发布商品 → 买家购买 → 返回 goods_{orderId} 会话 ID */
    private String createRealGoodsSession(AuthUser seller, AuthUser buyer) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "聊天测试商品-" + System.currentTimeMillis());
        form.put("description", "测试");
        form.put("categoryId", "1");
        form.put("price", "100");
        form.put("images", new org.springframework.core.io.ByteArrayResource("img".getBytes()) {
            @Override
            public String getFilename() {
                return "chat.jpg";
            }
        });
        assertOk(postMultipart("/goods", form, seller));
        ResponseEntity<Map> my = get("/goods/my?pageNum=1&pageSize=1", seller);
        Long goodsId = Long.parseLong(String.valueOf(
                ((java.util.List<Map<String, Object>>) ((Map<?, ?>) my.getBody().get("data")).get("records")).get(0).get("id")));
        assertOk(post("/goods/buy/" + goodsId, null, buyer));
        ResponseEntity<Map> orders = get("/goods/order/my?pageNum=1&pageSize=1", buyer);
        Long orderId = Long.parseLong(String.valueOf(
                ((java.util.List<Map<String, Object>>) ((Map<?, ?>) orders.getBody().get("data")).get("records")).get(0).get("id")));
        return "goods_" + orderId;
    }

    private void seedMessage(String sessionId, Long senderId, Long receiverId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setSenderId(senderId);
        msg.setReceiverId(receiverId);
        msg.setContent(content);
        msg.setMsgType(MessageType.TEXT);
        msg.setIsRead(IsReadStatus.UNREAD);
        chatService.saveMessage(msg);
    }

    @Test
    @DisplayName("回归验证：第三方用户读取他人会话被拒绝（缺陷已修复 → 绿）")
    void history_thirdParty_accessDenied() {
        AuthUser seller = newAuthedUser();
        AuthUser buyer = newAuthedUser();
        AuthUser intruder = newAuthedUser();
        String sessionId = createRealGoodsSession(seller, buyer);
        seedMessage(sessionId, seller.getUserId(), buyer.getUserId(), "机密信息");

        ResponseEntity<Map> resp = get("/chat/messages/" + sessionId, intruder);
        assertFail(resp);
    }

    @Test
    @DisplayName("会话参与人可正常读取历史消息")
    void history_participant_success() {
        AuthUser seller = newAuthedUser();
        AuthUser buyer = newAuthedUser();
        String sessionId = createRealGoodsSession(seller, buyer);
        seedMessage(sessionId, seller.getUserId(), buyer.getUserId(), "你好，请问在吗");

        ResponseEntity<Map> resp = get("/chat/messages/" + sessionId, buyer);
        assertOk(resp);
    }
}
