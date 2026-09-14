package com.rambo.chat;

import com.rambo.BaseApiTest;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.helper.AuthUser;
import com.rambo.infrastructure.auth.JwtUtil;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.converter.MappingJackson2MessageConverter;
import org.springframework.messaging.simp.stomp.StompFrameHandler;
import org.springframework.messaging.simp.stomp.StompHeaders;
import org.springframework.messaging.simp.stomp.StompSession;
import org.springframework.messaging.simp.stomp.StompSessionHandlerAdapter;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.messaging.WebSocketStompClient;

import java.lang.reflect.Type;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * WebSocket / STOMP 建连鉴权测试。
 *
 * <p>这是全项目此前<b>零覆盖</b>的链路（31 个测试文件中 WebSocket/Stomp/Destination 关键字零命中），
 * 但恰好是面试追问最密集的地方：CONNECT 帧为什么带 token、黑名单/禁用在哪校验、
 * 未认证连接能否发消息。本测试用真实 STOMP 客户端连真实端口，覆盖
 * {@link com.rambo.infrastructure.auth.WebSocketAuthInterceptor} 与
 * {@link com.rambo.infrastructure.auth.ChatAuthInterceptor} 的实际行为。</p>
 *
 * <p><b>为什么 token 放在 CONNECT 帧的 native header</b>：浏览器 WebSocket API 无法自定义
 * HTTP header，只能在 STOMP 帧里携带 —— 这是 WS 鉴权的标准做法，也是本测试要钉死的契约。</p>
 *
 * <p><b>端点说明</b>：生产/测试均注册 {@code /ws}（带 SockJS 兜底）。原生 WebSocket 直接连
 * {@code ws://host/ws} 即可（SockJS 的 {@code /websocket} 子路径是另一条通道）。</p>
 */
@DisplayName("WebSocket STOMP 建连鉴权")
class WebSocketAuthTest extends BaseApiTest {

    @Resource
    private JwtUtil jwtUtil;

    /** STOMP 交互超时（连不上/收不到时避免用例挂死） */
    private static final long TIMEOUT_SECONDS = 5;

    /**
     * STOMP 端点 URL。
     *
     * <p><b>两个易踩的点（本测试实测确认）</b>：
     * <ol>
     *   <li>项目配置了 {@code server.servlet.context-path=/api}（application.yml:94），
     *       WebSocket 端点因此是 <b>{@code /api/ws}</b> 而非 {@code /ws} —— 直接连 {@code /ws} 会得到 404；</li>
     *   <li>端点注册用了 {@code withSockJS()}，SockJS 的原生 WebSocket 传输路径是
     *       <b>{@code /api/ws/websocket}</b>（{@code /api/ws} 本身是 SockJS 的 info/协商入口，不接受 WS 升级）。</li>
     * </ol>
     * 这也顺带证实了「MVC 拦截器不作用于 WebSocket 路径」：WS 走独立的
     * {@code WebSocketHandlerMapping}，由 STOMP 的 ChannelInterceptor 链负责鉴权。</p>
     */
    private String wsUrl() {
        return "ws://localhost:" + port + "/api/ws/websocket";
    }

    /**
     * 建立 STOMP 连接并完成 CONNECT 握手。
     *
     * @param token           放入 CONNECT 帧 Authorization 头的 accessToken；null 表示不带
     * @param expectConnected true=期望握手成功；false=期望握手失败（鉴权拒绝）
     * @return 连接成功时返回 session；失败时返回 null
     */
    private StompSession connect(String token, boolean expectConnected) throws Exception {
        WebSocketStompClient client = new WebSocketStompClient(new StandardWebSocketClient());
        client.setMessageConverter(new MappingJackson2MessageConverter());

        StompHeaders connectHeaders = new StompHeaders();
        if (token != null) {
            connectHeaders.add("Authorization", "Bearer " + token);
        }

        AtomicReference<Throwable> error = new AtomicReference<>();
        StompSessionHandlerAdapter handler = new StompSessionHandlerAdapter() {
            @Override
            public void handleTransportError(StompSession session, Throwable exception) {
                error.set(exception);
            }
        };

        try {
            StompSession session = client.connectAsync(wsUrl(),
                            new WebSocketHttpHeaders(), connectHeaders, handler)
                    .get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (!expectConnected) {
                // 期望失败却成功了：交给断言报错，不在这里静默
                return session;
            }
            return session;
        } catch (Exception e) {
            if (expectConnected) {
                throw e;
            }
            // 期望失败：握手被拒是预期结果
            return null;
        }
    }

    // ==================== CONNECT 帧鉴权 ====================

    @Test
    @DisplayName("携带合法 AT 的连接应握手成功并可订阅")
    void connectWithValidToken() throws Exception {
        AuthUser user = newUser();

        StompSession session = connect(user.getAccessToken(), true);
        assertThat(session).as("合法 token 应能建立 STOMP 连接").isNotNull();
        assertThat(session.isConnected()).isTrue();

        session.disconnect();
    }

    @Test
    @DisplayName("缺少 Authorization 头的连接应被拒绝")
    void connectWithoutTokenRejected() throws Exception {
        StompSession session = connect(null, false);
        assertThat(session).as("未携带 token 的连接必须被拒绝").isNull();
    }

    @Test
    @DisplayName("伪造/损坏的 token 应被拒绝")
    void connectWithForgedTokenRejected() throws Exception {
        StompSession session = connect("eyJhbGciOiJIUzI1NiJ9.forged.signature", false);
        assertThat(session).as("伪造 token 的连接必须被拒绝").isNull();
    }

    @Test
    @DisplayName("已登出（加入 AT 黑名单）的 token 应被拒绝——签名有效但状态已失效")
    void connectWithBlacklistedTokenRejected() throws Exception {
        AuthUser user = newUser();
        String token = user.getAccessToken();

        // 先验证该 token 正常可用
        StompSession ok = connect(token, true);
        assertThat(ok).isNotNull();
        ok.disconnect();

        // 模拟登出：把 AT 写入黑名单（与 JwtInterceptor / WebSocketAuthInterceptor 同源 key）
        redis.opsForValue().set(PrefixConstants.AT_BLACKLIST + token, "1", 60, TimeUnit.SECONDS);

        try {
            StompSession rejected = connect(token, false);
            assertThat(rejected)
                    .as("黑名单中的 token 签名仍有效，但必须被 CONNECT 帧的状态校验拒绝")
                    .isNull();
        } finally {
            redis.delete(PrefixConstants.AT_BLACKLIST + token);
        }
    }

    @Test
    @DisplayName("账号被禁用的用户应被拒绝建连")
    void connectWithDisabledUserRejected() throws Exception {
        AuthUser user = newUser();
        String token = user.getAccessToken();

        redis.opsForValue().set(PrefixConstants.USER_DISABLED + user.getUserId(), "1", 60, TimeUnit.SECONDS);

        try {
            StompSession session = connect(token, false);
            assertThat(session).as("被禁用用户必须被拒绝建连").isNull();
        } finally {
            redis.delete(PrefixConstants.USER_DISABLED + user.getUserId());
        }
    }

    // ==================== SEND 帧兜底与消息收发 ====================

    /**
     * 订阅用户级目的地 {@code /user/queue/chat}。
     *
     * <p>注意订阅前缀：Spring 的 user destination 在服务端会自动加 {@code /user} 前缀，
     * 客户端订阅 {@code /user/queue/chat}，服务端 convertAndSendToUser 的目标是 {@code /queue/chat}。</p>
     */
    private BlockingQueue<Map> subscribeChat(StompSession session) throws Exception {
        BlockingQueue<Map> received = new LinkedBlockingQueue<>();
        session.subscribe("/user/queue/chat", new StompFrameHandler() {
            @Override
            public Type getPayloadType(StompHeaders headers) {
                return Map.class;
            }

            @Override
            public void handleFrame(StompHeaders headers, Object payload) {
                received.add((Map) payload);
            }
        });
        // 给订阅注册留出时间（SUBSCRIBE 是异步帧）
        Thread.sleep(300);
        return received;
    }

    @Test
    @DisplayName("已认证连接发送消息，对端应能收到（验证 SEND 放行 + 推送链路）")
    void authenticatedSendDeliversToPeer() throws Exception {
        // 准备一个任务会话：publisher 发布任务，applicant 申请并被接受 → 会话创建
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();
        String sessionId = prepareTaskSession(publisher, applicant);

        StompSession pubSession = connect(publisher.getAccessToken(), true);
        StompSession appSession = connect(applicant.getAccessToken(), true);
        try {
            BlockingQueue<Map> applicantInbox = subscribeChat(appSession);

            // 发布者向 applicant 发消息
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("content", "你好，任务什么时候方便？");
            payload.put("msgType", 0);
            pubSession.send("/app/chat.send", payload);

            Map received = applicantInbox.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(received)
                    .as("对端必须收到实时推送（验证 SEND 放行且 receiverId 由服务端推导）")
                    .isNotNull();
            assertThat(received.get("content")).isEqualTo("你好，任务什么时候方便？");
            assertThat(String.valueOf(received.get("senderId")))
                    .as("发送者应为 publisher 本人，来自 Principal 而非客户端传值")
                    .isEqualTo(String.valueOf(publisher.getUserId()));
        } finally {
            pubSession.disconnect();
            appSession.disconnect();
        }
    }

    @Test
    @DisplayName("客户端传入的 receiverId 被忽略，接收者由服务端按会话推导")
    void clientSuppliedReceiverIdIsIgnored() throws Exception {
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();
        AuthUser attacker = newAuthedUser();
        String sessionId = prepareTaskSession(publisher, applicant);

        StompSession pubSession = connect(publisher.getAccessToken(), true);
        StompSession appSession = connect(applicant.getAccessToken(), true);
        try {
            BlockingQueue<Map> applicantInbox = subscribeChat(appSession);

            // 恶意/过期的客户端传值：试图把消息投给 attacker
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("receiverId", attacker.getUserId());
            payload.put("content", "越权测试");
            payload.put("msgType", 0);
            pubSession.send("/app/chat.send", payload);

            Map received = applicantInbox.poll(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertThat(received)
                    .as("消息必须投给会话真实的另一方（applicant），而非客户端指定的 receiverId")
                    .isNotNull();
            assertThat(String.valueOf(received.get("receiverId")))
                    .as("receiverId 必须被服务端覆盖为 applicant")
                    .isEqualTo(String.valueOf(applicant.getUserId()));
            assertThat(String.valueOf(received.get("receiverId")))
                    .as("绝不能投给客户端传入的第三方")
                    .isNotEqualTo(String.valueOf(attacker.getUserId()));
        } finally {
            pubSession.disconnect();
            appSession.disconnect();
        }
    }

    @Test
    @DisplayName("非会话参与方发送消息应被拒绝（越权防护）")
    void nonParticipantCannotSend() throws Exception {
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();
        AuthUser outsider = newAuthedUser();
        String sessionId = prepareTaskSession(publisher, applicant);

        StompSession outsiderSession = connect(outsider.getAccessToken(), true);
        StompSession appSession = connect(applicant.getAccessToken(), true);
        try {
            BlockingQueue<Map> applicantInbox = subscribeChat(appSession);

            // 局外人拿合法 token 连上，但传他人会话的 sessionId
            Map<String, Object> payload = new HashMap<>();
            payload.put("sessionId", sessionId);
            payload.put("content", "我不该看到这个会话");
            payload.put("msgType", 0);
            outsiderSession.send("/app/chat.send", payload);

            Map received = applicantInbox.poll(2, TimeUnit.SECONDS);
            assertThat(received)
                    .as("非参与方的消息不得被投递（getOtherParticipant 的参与方校验必须拦住）")
                    .isNull();
        } finally {
            outsiderSession.disconnect();
            appSession.disconnect();
        }
    }

    // ==================== 会话准备辅助 ====================

    /** 会话标识前缀，与 ChatServiceImpl 的解析规则保持一致 */
    private static final String TASK_SESSION_PREFIX = "task_";

    /** 创建任务所需地址，返回 addressId（地址接口登录即可，无需学生认证） */
    private Long createAddress(AuthUser user) {
        Map<String, Object> body = new HashMap<>();
        body.put("receiverName", "测试");
        body.put("receiverPhone", user.getPhone());
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detailAddress", "测试路 1 号");
        body.put("isDefault", 0);
        assertOk(post("/address", body, user));
        java.util.List<Map<String, Object>> records =
                (java.util.List<Map<String, Object>>) get("/address/list", user).getBody().get("data");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    /**
     * 造一个真实的任务聊天会话：publisher 发布 → applicant 申请 → publisher 接受。
     * 接受后 TaskWorkflowServiceImpl 会 createIfNotExist 建立聊天会话。
     *
     * @return sessionId（形如 {@code task_{taskId}}）
     */
    private String prepareTaskSession(AuthUser publisher, AuthUser applicant) {
        Long addressId = createAddress(publisher);

        // 1. 发布任务
        Map<String, Object> taskBody = new HashMap<>();
        taskBody.put("title", "WS测试任务-" + System.currentTimeMillis());
        taskBody.put("description", "用于验证 WebSocket 聊天链路的测试任务");
        taskBody.put("reward", 10);
        taskBody.put("categoryId", 1);
        taskBody.put("addressId", addressId);
        taskBody.put("deadline", "2026-12-31 18:00:00");
        assertOk(postWithSubmitToken("/task", taskBody, publisher, "publishTask"));

        // 2. 取刚发布的任务 ID
        Long taskId = latestPublishedTaskId(publisher);

        // 3. applicant 申请（reason 走 query 参数）
        assertOk(postWithSubmitToken("/task/application/" + taskId + "?reason=我来做",
                null, applicant, "applyForTask"));

        // 4. 取申请 ID（申请列表按 taskId 查询）
        Long applicationId = latestApplicationId(publisher, taskId);

        // 5. publisher 接受申请（触发 createIfNotExist 建会话）
        assertOk(put("/task/application/" + applicationId + "/accept", null, publisher));

        return TASK_SESSION_PREFIX + taskId;
    }

    private Long latestPublishedTaskId(AuthUser user) {
        ResponseEntity<Map> resp = get("/task/my-published?pageNum=1&pageSize=10", user);
        assertOk(resp);
        java.util.List<Map<String, Object>> records =
                (java.util.List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        assertThat(records).isNotEmpty();
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Long latestApplicationId(AuthUser user, Long taskId) {
        ResponseEntity<Map> resp = get("/task/application/" + taskId + "?pageNum=1&pageSize=1", user);
        assertOk(resp);
        java.util.List<Map<String, Object>> records =
                (java.util.List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        assertThat(records).isNotEmpty();
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }
}
