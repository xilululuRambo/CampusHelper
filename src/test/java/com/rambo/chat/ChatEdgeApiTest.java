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
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 聊天模块补充测试：历史消息分页排序、深分页防御、标记已读作用域、非法会话拒绝。
 * 会话数据通过真实商品购买链路构造（goods_{orderId}），消息经 Service 直落 Mongo。
 */
class ChatEdgeApiTest extends BaseApiTest {

    @Resource
    private ChatService chatService;
    @Resource
    private MongoTemplate mongoTemplate;

    /** 造一条真实商品会话：卖家发布商品 → 买家购买 → 返回 goods_{orderId} 会话 ID */
    private String createRealGoodsSession(AuthUser seller, AuthUser buyer) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "聊天补充测试-" + System.currentTimeMillis());
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
                ((List<Map<String, Object>>) ((Map<?, ?>) my.getBody().get("data")).get("records")).get(0).get("id")));
        assertOk(post("/goods/buy/" + goodsId, null, buyer));
        ResponseEntity<Map> orders = get("/goods/order/my?pageNum=1&pageSize=1", buyer);
        Long orderId = Long.parseLong(String.valueOf(
                ((List<Map<String, Object>>) ((Map<?, ?>) orders.getBody().get("data")).get("records")).get(0).get("id")));
        return "goods_" + orderId;
    }

    private void seedMessage(String sessionId, Long senderId, Long receiverId, String content) {
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(sessionId);
        msg.setSenderId(senderId);
        msg.setReceiverId(receiverId);
        msg.setContent(content);
        msg.setMsgType(MessageType.TEXT);
        chatService.saveMessage(msg);
    }

    private ChatMessage findMsg(String sessionId, String content) {
        return mongoTemplate.findOne(
                Query.query(Criteria.where("sessionId").is(sessionId).and("content").is(content)),
                ChatMessage.class);
    }

    @Test
    @DisplayName("历史消息：按时间倒序分页，最新消息在前，total 正确")
    void history_pagination_newestFirst() throws InterruptedException {
        AuthUser seller = newAuthedUser();
        AuthUser buyer = newAuthedUser();
        String sessionId = createRealGoodsSession(seller, buyer);
        seedMessage(sessionId, seller.getUserId(), buyer.getUserId(), "第1条");
        Thread.sleep(5);
        seedMessage(sessionId, seller.getUserId(), buyer.getUserId(), "第2条");
        Thread.sleep(5);
        seedMessage(sessionId, buyer.getUserId(), seller.getUserId(), "第3条");

        // 购买成功时系统会自动写入一条欢迎消息（卖家→买家），故会话共 4 条
        String systemMsg = com.rambo.common.constants.MessageConstants.GOODS_ORDER_AGREE_SESSION;
        ResponseEntity<Map> resp = get("/chat/messages/" + sessionId + "?pageNum=1&pageSize=2", buyer);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        // total 为 long 被序列化为 String
        assertThat(Long.parseLong(String.valueOf(data.get("total")))).isEqualTo(4);
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).hasSize(2);
        assertThat(records.get(0).get("content")).isEqualTo("第3条"); // 最新在前
        assertThat(records.get(1).get("content")).isEqualTo("第2条");

        // 第二页返回最早 2 条：第1条 + 购买自动生成的系统消息
        ResponseEntity<Map> page2 = get("/chat/messages/" + sessionId + "?pageNum=2&pageSize=2", buyer);
        assertOk(page2);
        List<Map<String, Object>> r2 = (List<Map<String, Object>>)
                ((Map<?, ?>) page2.getBody().get("data")).get("records");
        assertThat(r2).hasSize(2);
        assertThat(r2.get(0).get("content")).isEqualTo("第1条");
        assertThat(r2.get(1).get("content")).isEqualTo(systemMsg);
    }

    @Test
    @DisplayName("历史消息：pageSize 超限被钳制到上限，不报错（深分页防御回归）")
    void history_pageSizeLimit_clamped() {
        AuthUser seller = newAuthedUser();
        AuthUser buyer = newAuthedUser();
        String sessionId = createRealGoodsSession(seller, buyer);
        seedMessage(sessionId, seller.getUserId(), buyer.getUserId(), "防深分页");

        ResponseEntity<Map> resp = get("/chat/messages/" + sessionId + "?pageNum=1&pageSize=99999", buyer);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records.size()).isLessThanOrEqualTo(100);
    }

    @Test
    @DisplayName("标记已读：仅将发给自己的未读消息置为已读，不影响对方消息")
    void markRead_onlyMyMessages() {
        AuthUser seller = newAuthedUser();
        AuthUser buyer = newAuthedUser();
        String sessionId = createRealGoodsSession(seller, buyer);
        seedMessage(sessionId, seller.getUserId(), buyer.getUserId(), "发给买家-已读");
        seedMessage(sessionId, buyer.getUserId(), seller.getUserId(), "发给卖家-保持未读");

        assertOk(put("/chat/messages/" + sessionId + "/read", null, buyer));

        ChatMessage toBuyer = findMsg(sessionId, "发给买家-已读");
        ChatMessage toSeller = findMsg(sessionId, "发给卖家-保持未读");
        assertThat(toBuyer.getIsRead()).isEqualTo(IsReadStatus.READ);
        assertThat(toSeller.getIsRead()).isEqualTo(IsReadStatus.UNREAD);
    }

    @Test
    @DisplayName("历史消息：未知会话类型（非 task_/goods_ 前缀）拒绝访问")
    void history_unknownSession_denied() {
        AuthUser user = newAuthedUser();
        assertFailWithMsg(get("/chat/messages/foo_12345", user), "无权");
    }

    @Test
    @DisplayName("标记已读：不存在的会话安全返回（无匹配即空更新）")
    void markRead_unknownSession_safe() {
        AuthUser user = newAuthedUser();
        assertOk(put("/chat/messages/foo_999999/read", null, user));
    }
}
