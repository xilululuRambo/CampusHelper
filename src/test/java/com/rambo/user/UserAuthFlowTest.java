package com.rambo.user;

import com.rambo.BaseApiTest;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.auth.JwtUtil;
import com.rambo.helper.AuthUser;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFail;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * JWT 全链路安全测试：双令牌轮换 / RT 重放踢设备 / 登出拉黑 / 多设备管理 / 管理员刷新。
 *
 * 测试库 access-expiration=30min，无法等待真实过期；利用 JwtUtil.createToken 的公开
 * 过期入参构造「已过期但签名合法」的 AT（exp 为负），完整走 ExpiredJwtException 分支。
 */
class UserAuthFlowTest extends BaseApiTest {

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private RedissonClient redissonClient;

    /** 构造已过期的用户 Access Token（claims 与真实登录一致：id + deviceId） */
    private String expiredUserAt(AuthUser user) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", user.getUserId().toString());
        claims.put("deviceId", user.getDeviceId());
        return jwtUtil.createToken(claims, -1000);
    }

    /** 带「过期 AT + Refresh-Token 头」的请求（触发自动刷新分支） */
    private ResponseEntity<Map> getWithRefresh(String path, String expiredAt, String refreshToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(expiredAt);
        headers.add("Refresh-Token", refreshToken);
        return rest.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }

    /** 同一手机号二次登录 → 第二台设备（与首设备并存，Hash 两个 field） */
    private AuthUser secondDeviceLogin(AuthUser first) {
        // 首设备登录后验证码已删、冷却 key 仍在，先清冷却再重新发码
        redis.delete(PrefixConstants.CODE_COOLDOWN_TIME_PREFIX + first.getPhone());
        assertOk(post("/user/code?phone=" + first.getPhone(), null, null));
        String code = redis.opsForValue().get(PrefixConstants.CODE_PREFIX + first.getPhone());
        Map<String, String> body = Map.of("phone", first.getPhone(), "code", code);
        ResponseEntity<Map> resp = post("/user/login", body, null);
        assertOk(resp);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        return new AuthUser(first.getPhone(), first.getUserId(),
                (String) data.get("accessToken"), (String) data.get("refreshToken"),
                (String) data.get("deviceId"));
    }

    // ==================== 用户 Refresh Token Rotation ====================

    @Test
    @DisplayName("AT 过期 + Refresh-Token 头：自动刷新并返回新双令牌（RT Rotation）")
    void refresh_expiredAt_rotatesTokens() {
        AuthUser user = newUser();
        ResponseEntity<Map> resp = getWithRefresh("/user/me", expiredUserAt(user), user.getRefreshToken());

        // 刷新成功：业务 200 且响应头带新双令牌
        assertOk(resp);
        String newAt = resp.getHeaders().getFirst("Authorization");
        String newRt = resp.getHeaders().getFirst("Refresh-Token");
        assertThat(newAt).as("刷新后应返回新 Access Token").isNotBlank();
        assertThat(newRt).as("刷新后应返回新 Refresh Token").isNotBlank();
        assertThat(newAt).isNotEqualTo(user.getAccessToken());
        assertThat(newRt).isNotEqualTo(user.getRefreshToken());

        // Redis 中该设备 field 已轮换为新 RT
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + user.getUserId())
                .get(user.getDeviceId())).isEqualTo(newRt);
    }

    @Test
    @DisplayName("AT 过期但未带 Refresh-Token 头：拒绝（登录失效）")
    void refresh_withoutRefreshToken_fails() {
        AuthUser user = newUser();
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(expiredUserAt(user));
        ResponseEntity<Map> resp = rest.exchange(baseUrl() + "/user/me",
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
        assertFail(resp);
    }

    @Test
    @DisplayName("旧 Refresh Token 重放：拒绝刷新并踢出该设备（防盗用）")
    void refresh_replayedRefreshToken_kicksDevice() {
        AuthUser user = newUser();

        // 1. 首次刷新成功，Redis 中 field 已轮换为新 RT
        ResponseEntity<Map> first = getWithRefresh("/user/me", expiredUserAt(user), user.getRefreshToken());
        assertOk(first);

        // 2. 用已被轮换掉的旧 RT 再次刷新 → 重放判定 → 踢设备
        ResponseEntity<Map> replay = getWithRefresh("/user/me", expiredUserAt(user), user.getRefreshToken());
        assertFail(replay);

        // 3. 该设备 field 已被删除（后续即使 RT 正确也无法续期）
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + user.getUserId())
                .containsKey(user.getDeviceId())).isFalse();
    }

    @Test
    @DisplayName("AT 过期 + 伪造 Refresh-Token：拒绝（签名校验失败）")
    void refresh_withForgedRefreshToken_fails() {
        AuthUser user = newUser();
        ResponseEntity<Map> resp = getWithRefresh("/user/me", expiredUserAt(user), "forged.refresh.token");
        assertFail(resp);
        // 伪造 RT 不触发踢设备（解析失败即拒绝，不清除现场）
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + user.getUserId())
                .containsKey(user.getDeviceId())).isTrue();
    }

    // ==================== 登出与黑名单 ====================

    @Test
    @DisplayName("登出：RT 删除 + 旧 AT 进入黑名单立即失效")
    void logout_removesRtAndBlacklistsAt() {
        // /user/logout 无 @NoAuthAnnotation，需已认证用户
        AuthUser user = newAuthedUser();
        assertOk(get("/user/logout", user));

        // RT 已从 Hash 删除
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + user.getUserId())
                .containsKey(user.getDeviceId())).isFalse();

        // 旧 AT 已被拉黑：即使未过期也立即失效
        assertFail(get("/user/me", user));
    }

    @Test
    @DisplayName("踢掉所有设备：Hash 清空、当前设备 AT 拉黑、其他设备无法续期")
    void logoutAll_removesAllDeviceTokens() {
        // /user/logout-all 无 @NoAuthAnnotation，需已认证用户
        AuthUser dev1 = newAuthedUser();
        AuthUser dev2 = secondDeviceLogin(dev1);
        // 两个设备的 RT 均已落库
        var map = redissonClient.getMapCache(PrefixConstants.USER_TOKENS + dev1.getUserId());
        assertThat(map.containsKey(dev1.getDeviceId())).isTrue();
        assertThat(map.containsKey(dev2.getDeviceId())).isTrue();

        // 设备 1 发起全设备下线
        assertOk(post("/user/logout-all", null, dev1));

        // 整个 Hash 被删除
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + dev1.getUserId()).size())
                .isZero();

        // 当前设备 AT 被拉黑，立即失效
        assertFail(get("/user/me", dev1));

        // 设备 2 的 AT 未过期且未被拉黑，仍可访问；但其 RT 已删，无法刷新续期
        assertOk(get("/user/me", dev2));
        assertFail(getWithRefresh("/user/me", expiredUserAt(dev2), dev2.getRefreshToken()));
    }

    @Test
    @DisplayName("踢指定设备：仅该设备 RT 删除，其他设备不受影响")
    void logoutDevice_removesOnlyTargetDevice() {
        // /user/logout-device 无 @NoAuthAnnotation，需已认证用户
        AuthUser dev1 = newAuthedUser();
        AuthUser dev2 = secondDeviceLogin(dev1);

        // 设备 2 踢掉设备 1
        assertOk(post("/user/logout-device?deviceId=" + dev1.getDeviceId(), null, dev2));

        var map = redissonClient.getMapCache(PrefixConstants.USER_TOKENS + dev1.getUserId());
        assertThat(map.containsKey(dev1.getDeviceId())).as("被踢设备 RT 应删除").isFalse();
        assertThat(map.containsKey(dev2.getDeviceId())).as("其他设备 RT 应保留").isTrue();

        // 被踢设备即使带 RT 也无法续期
        assertFail(getWithRefresh("/user/me", expiredUserAt(dev1), dev1.getRefreshToken()));
    }

    // ==================== 管理员刷新 ====================

    @Test
    @DisplayName("管理员 AT 过期 + RT 头：自动刷新并返回新双令牌（保留原角色）")
    void adminRefresh_expiredAt_rotatesTokens() {
        AuthUser su = adminLogin();
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", su.getUserId().toString());
        claims.put("role", EnumConstants.ROLE_SUPER_ADMIN);
        String expiredAdminAt = jwtUtil.createToken(claims, -1000);

        ResponseEntity<Map> resp = getWithRefresh("/admin/me", expiredAdminAt, su.getRefreshToken());

        assertOk(resp);
        String newAt = resp.getHeaders().getFirst("Authorization");
        String newRt = resp.getHeaders().getFirst("Refresh-Token");
        assertThat(newAt).as("刷新后应返回新 Access Token").isNotBlank();
        assertThat(newRt).as("刷新后应返回新 Refresh Token").isNotBlank();
        // 刷新后角色保持超管，detail 接口仍可访问（普通管理员会被拒）
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(Integer.valueOf(String.valueOf(data.get("role")))).isZero();
    }

    /** 种子超管登录（account=13900000001 / admin123456），返回 AuthUser */
    private AuthUser adminLogin() {
        Map<String, Object> body = new HashMap<>();
        body.put("account", "13900000001");
        body.put("password", "admin123456");
        ResponseEntity<Map> resp = post("/admin/login", body, null);
        assertOk(resp);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        String at = (String) data.get("accessToken");
        String rt = (String) data.get("refreshToken");
        Long id = Long.parseLong(jwtUtil.parseStringClaim(at));
        return new AuthUser(String.valueOf(id), id, at, rt, "admin");
    }
}
