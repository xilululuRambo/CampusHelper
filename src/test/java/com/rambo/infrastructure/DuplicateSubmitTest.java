package com.rambo.infrastructure;

import com.rambo.BaseApiTest;
import com.rambo.common.constants.CodeConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.RedisKeyConstants;
import com.rambo.helper.AssertHelper;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 防重复提交全链路测试：{@code CommonController#getSubmitToken} 签发 → {@code DuplicateSubmitAspect} 消费。
 *
 * <p><b>为什么单开一个类</b>：{@code CommonApiTest} 只测了「令牌能签发出来」，
 * {@code TaskApiTest} / {@code WebSocketAuthTest} 只是把令牌当作调接口的入场券用。
 * 真正的防重语义——**同一个令牌只能用一次、第二次必须被拦下**——以及三条异常分支
 * （缺头 / 令牌不匹配 / 跨场景串用）此前从未被断言过。这三条分支的失效方式都是静默放行：
 * 缺头分支失效 → 前端漏传头时接口直接可用（防重形同虚设）；
 * 删除原子性失效 → 并发双提交双双通过，正好是防重机制唯一要防的场景。</p>
 *
 * <p><b>为什么要验 Redis key 被删除</b>：切面用的是 {@code cacheClient.delete(key)}
 * 的**返回值**判定是否重复（删除成功 = 首次；删除失败 = 已被用过）。
 * 这是「一令牌一用」的原子基础，必须同时验证「消费后 key 确实消失」，
 * 否则无法区分「令牌被删了」与「令牌压根没存进去」。</p>
 */
class DuplicateSubmitTest extends BaseApiTest {

    private static final String HEADER = "X-Submit-Token";

    /** 发布任务接口（@PreventDuplicate(scene = "publishTask")）所需的合法请求体 */
    private Map<String, Object> taskBody(AuthUser user) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "防重测试-" + System.nanoTime());
        body.put("description", "测试描述");
        body.put("reward", 5);
        body.put("categoryId", 1);
        body.put("addressId", createAddress(user));
        body.put("deadline", "2026-12-31 18:00:00");
        return body;
    }

    /** 建一个地址（地址接口免学生认证，登录即可），发布任务需要它 */
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
        ResponseEntity<Map> list = get("/address/list", user);
        var records = (java.util.List<Map<String, Object>>) list.getBody().get("data");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    /** 带指定令牌发请求（不自动签发，用于精确控制令牌值） */
    private ResponseEntity<Map> postWithRawToken(String path, Object body, AuthUser user, String token) {
        HttpHeaders headers = authHeaders(user);
        if (token != null) {
            headers.add(HEADER, token);
        }
        return rest.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    private String tokenKey(String scene, AuthUser user, String token) {
        return RedisKeyConstants.SUBMIT_TOKEN_PREFIX + scene + ":" + user.getUserId() + ":" + token;
    }

    // ==================== 正常闭环 ====================

    @Test
    @DisplayName("首次提交：令牌被消费，Redis key 消失")
    void firstSubmit_consumesToken() {
        AuthUser user = newAuthedUser();
        Map<String, Object> body = taskBody(user);
        String token = submitToken("publishTask", user);
        String key = tokenKey("publishTask", user, token);

        assertThat(redis.hasKey(key)).as("签发后令牌必须在 Redis 中").isTrue();

        assertOk(postWithRawToken("/task", body, user, token));

        assertThat(redis.hasKey(key))
                .as("切面靠 delete 的返回值判定首次/重复，消费后 key 必须真的消失——"
                        + "否则同一个令牌可无限次使用，防重机制整体失效")
                .isFalse();
    }

    @Test
    @DisplayName("重复提交：同一令牌第二次被拦下，报「请勿重复提交」")
    void secondSubmit_sameToken_blocked() {
        AuthUser user = newAuthedUser();
        Map<String, Object> body = taskBody(user);
        String token = submitToken("publishTask", user);

        // 第一次：放行并消耗令牌
        assertOk(postWithRawToken("/task", body, user, token));

        // 第二次：同一令牌再打一次
        ResponseEntity<Map> second = postWithRawToken("/task", body, user, token);
        AssertHelper.assertFailWithMsg(second, MessageConstants.DUPLICATE_SUBMIT);
    }

    @Test
    @DisplayName("跨用户：A 的令牌不能给 B 用（key 含 userId，天然隔离）")
    void token_crossUser_notInterchangeable() {
        AuthUser userA = newAuthedUser();
        AuthUser userB = newAuthedUser();

        String tokenOfA = submitToken("publishTask", userA);

        // B 拿着 A 的令牌提交：key 拼的是 B 自己的 userId → 查不到该 key → delete 返回 false → 视为重复
        ResponseEntity<Map> resp = postWithRawToken("/task", taskBody(userB), userB, tokenOfA);
        AssertHelper.assertFailWithMsg(resp, MessageConstants.DUPLICATE_SUBMIT);

        // 而 A 自己的令牌仍然有效（B 的失败请求不得消耗 A 的令牌）
        assertThat(redis.hasKey(tokenKey("publishTask", userA, tokenOfA)))
                .as("他人持令牌提交失败，不应误删令牌持有者的 key")
                .isTrue();
    }

    @Test
    @DisplayName("跨场景：publishTask 的令牌不能用于 applyForTask（scene 参与 key 拼接）")
    void token_crossScene_rejected() {
        AuthUser publisher = newAuthedUser();
        AuthUser applicant = newAuthedUser();

        // 先发一个任务，作为申请目标
        Long taskId = publishAndGetTaskId(publisher);

        // 取 publishTask 场景的令牌，却拿去打 applyForTask 场景的接口
        String wrongSceneToken = submitToken("publishTask", applicant);
        ResponseEntity<Map> resp = postWithRawToken(
                "/task/application/" + taskId + "?reason=我来做", null, applicant, wrongSceneToken);

        AssertHelper.assertFailWithMsg(resp, MessageConstants.DUPLICATE_SUBMIT);
    }

    @Test
    @DisplayName("重新签发：同一用户可拿到多个独立令牌，互不影响")
    void reissue_givesIndependentTokens() {
        AuthUser user = newAuthedUser();
        String first = submitToken("publishTask", user);
        String second = submitToken("publishTask", user);

        assertThat(first)
                .as("令牌用 UUID，重复签发必须产生不同值（否则同一用户连着提交两次会被误判重复）")
                .isNotEqualTo(second);
        assertThat(redis.hasKey(tokenKey("publishTask", user, first))).isTrue();
        assertThat(redis.hasKey(tokenKey("publishTask", user, second))).isTrue();
    }

    @Test
    @DisplayName("令牌 TTL：5 分钟过期（与 NumConstants 常量对齐）")
    void token_hasConfiguredTtl() {
        AuthUser user = newAuthedUser();
        String token = submitToken("publishTask", user);
        String key = tokenKey("publishTask", user, token);

        long remainMs = redis.getExpire(key, java.util.concurrent.TimeUnit.MILLISECONDS);

        assertThat(remainMs)
                .as("令牌必须有有限 TTL，否则 Redis 里会堆积永不清理的 key")
                .isGreaterThan(0);
        long expectedMs = NumConstants.SUBMIT_TOKEN_EXPIRE_MINUTES * 60_000L;
        assertThat(remainMs)
                .as("TTL 应接近 %s 分钟（留 10 秒误差容忍网络与执行耗时）",
                        NumConstants.SUBMIT_TOKEN_EXPIRE_MINUTES)
                .isBetween(expectedMs - 10_000L, expectedMs);
    }

    // ==================== 异常分支 ====================

    @Test
    @DisplayName("缺令牌头：被拒且报系统错误（不是静默放行）")
    void missingHeader_rejected() {
        AuthUser user = newAuthedUser();

        ResponseEntity<Map> resp = postWithRawToken("/task", taskBody(user), user, null);

        AssertHelper.assertFailWithMsg(resp, MessageConstants.SYSTEM_ERROR);
    }

    @Test
    @DisplayName("空字符串令牌头：同样被拒（hasText 判定，非仅判 null）")
    void blankHeader_rejected() {
        AuthUser user = newAuthedUser();

        ResponseEntity<Map> resp = postWithRawToken("/task", taskBody(user), user, "");

        AssertHelper.assertFailWithMsg(resp, MessageConstants.SYSTEM_ERROR);
    }

    @Test
    @DisplayName("伪造令牌：Redis 无此 key → 被当作重复提交拒绝")
    void forgedToken_rejected() {
        AuthUser user = newAuthedUser();

        // 从未签发过的随机令牌：delete 返回 false
        ResponseEntity<Map> resp = postWithRawToken("/task", taskBody(user), user, "forged-token-never-issued");
        AssertHelper.assertFailWithMsg(resp, MessageConstants.DUPLICATE_SUBMIT);
    }

    @Test
    @DisplayName("执行顺序实测：@RequestBody 参数解析失败早于切面（令牌未校验即报参数错误）")
    void bodyParsingFailure_happensBeforeTokenCheck() {
        AuthUser user = newAuthedUser();

        // 请求体刻意缺 deadline 等必填项，且**不带**令牌头
        Map<String, Object> invalidBody = new HashMap<>();
        invalidBody.put("description", "只有描述");

        ResponseEntity<Map> resp = postWithRawToken("/task", invalidBody, user, null);

        // 实测结论（本用例初版按「切面先于参数校验」写，被真实响应推翻，故按事实改写）：
        //   @Valid @RequestBody 的校验由 RequestResponseBodyMethodProcessor 在「参数解析阶段」完成，
        //   发生在 DispatcherServlet 调用 handler 之前；而 @Around("@annotation(pd)") 切的是
        //   Controller 方法的调用点，必然更晚。因此请求体畸形时，根本走不到缺令牌分支。
        assertThat((Integer) resp.getBody().get("code"))
                .as("参数解析失败必须先于切面暴露——若这里拿到 %s（缺少令牌），"
                        + "说明切面被提到了方法参数解析之前，属于拦截顺序异常",
                        MessageConstants.SYSTEM_ERROR)
                .isEqualTo(CodeConstants.PARAM_ERROR);
        assertThat((String) resp.getBody().get("msg"))
                .as("报错应指向具体缺失字段，而不是笼统的令牌错误")
                .contains("不能为空");
    }

    @Test
    @DisplayName("执行顺序实测：请求体合法时，缺令牌才轮到切面拦截")
    void validBody_missingToken_reachesAspect() {
        AuthUser user = newAuthedUser();

        // 请求体完全合法（含 addressId 等），只是不带令牌头
        ResponseEntity<Map> resp = postWithRawToken("/task", taskBody(user), user, null);

        AssertHelper.assertFailWithMsg(resp, MessageConstants.SYSTEM_ERROR);
    }

    // ==================== 并发：防重机制的核心场景 ====================

    @Test
    @DisplayName("并发双提交：同一令牌 n 个线程同时打，只有 1 个成功")
    void concurrentSubmits_onlyOneSucceeds() throws Exception {
        AuthUser user = newAuthedUser();
        Map<String, Object> body = taskBody(user);
        String token = submitToken("publishTask", user);

        int threads = 6;
        java.util.concurrent.CountDownLatch startGate = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicInteger okCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicInteger dupCount = new java.util.concurrent.atomic.AtomicInteger();
        java.util.List<Thread> workers = new java.util.ArrayList<>();

        for (int i = 0; i < threads; i++) {
            Thread t = new Thread(() -> {
                try {
                    startGate.await();
                    ResponseEntity<Map> resp = postWithRawToken("/task", body, user, token);
                    Object code = resp.getBody() == null ? null : resp.getBody().get("code");
                    if (CodeConstants.SUCCESS.equals(code)) {
                        okCount.incrementAndGet();
                    } else if (MessageConstants.DUPLICATE_SUBMIT.equals(resp.getBody().get("msg"))) {
                        dupCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    // 并发下的网络/连接异常不计入统计，只关心成功与重复两类结论
                }
            });
            workers.add(t);
            t.start();
        }
        startGate.countDown();
        for (Thread t : workers) {
            t.join(30_000);
        }

        assertThat(okCount.get())
                .as("防重复提交的唯一目的就是让并发重复请求只通过一个；"
                        + "若这里 > 1，说明「DELETE 返回值为 true」的原子判定被打破——"
                        + "实际是 delete 二次执行都返回了 true（如误改成先 get 再 delete）")
                .isEqualTo(1);
        assertThat(dupCount.get())
                .as("%s 个并发请求中，除胜出者外其余都必须被判定为重复提交", threads)
                .isEqualTo(threads - 1);
    }

    // ==================== 辅助 ====================

    /** 发布一个任务并返回其 id（用正规的令牌流程） */
    private Long publishAndGetTaskId(AuthUser user) {
        assertOk(postWithSubmitToken("/task", taskBody(user), user, "publishTask"));
        ResponseEntity<Map> myList = get("/task/my-published?pageNum=1&pageSize=1", user);
        var records = (java.util.List<Map<String, Object>>)
                ((Map<?, ?>) myList.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }
}
