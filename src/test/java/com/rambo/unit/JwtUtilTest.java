package com.rambo.unit;

import com.rambo.infrastructure.config.JwtProperties;
import com.rambo.infrastructure.auth.JwtUtil;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * JWT 工具单元测试（纯 JUnit + 反射注入配置，无 Spring 上下文）。
 *
 * 覆盖：双令牌生成、claims 往返、过期校验、签名篡改拒绝、损坏/空 token、
 * getExpiration 精确性。测试密钥与 application-test.yml 保持一致。
 */
class JwtUtilTest {

    private static final String SECRET = "test-secret-key-for-campus-helper-api-tests-0123456789";
    private static final long ACCESS_MS = 30 * 60 * 1000L;   // 30 分钟
    private static final long REFRESH_MS = 7 * 24 * 3600 * 1000L; // 7 天

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() throws Exception {
        JwtProperties props = new JwtProperties();
        props.setSecret(SECRET);
        props.setAccessExpiration(ACCESS_MS);
        props.setRefreshExpiration(REFRESH_MS);

        jwtUtil = new JwtUtil();
        Field f = JwtUtil.class.getDeclaredField("jwtProperties");
        f.setAccessible(true);
        f.set(jwtUtil, props);
    }

    private Map<String, Object> userClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", "10001");
        claims.put("deviceId", "7");
        return claims;
    }

    private Map<String, Object> adminClaims() {
        Map<String, Object> claims = new HashMap<>();
        claims.put("id", "1");
        claims.put("role", "SUPER_ADMIN");
        return claims;
    }

    @Test
    @DisplayName("生成 Access Token：可解析且 claims 完整")
    void createAccessToken_parseable() {
        String token = jwtUtil.createAccessToken(userClaims());
        assertThat(token).isNotBlank();

        Map<String, Object> parsed = jwtUtil.parseToken(token);
        assertThat(parsed.get("id")).isEqualTo("10001");
        assertThat(parsed.get("deviceId")).isEqualTo("7");
        // jti 存在且非空（防同秒重复 token 的关键）
        assertThat(parsed.get("jti")).isNotNull();
    }

    @Test
    @DisplayName("生成 Refresh Token：有效期远长于 Access Token")
    void createRefreshToken_longerExpiry() {
        String at = jwtUtil.createAccessToken(userClaims());
        String rt = jwtUtil.createRefreshToken(userClaims());

        long atMs = jwtUtil.getExpiration(at).getTime() - System.currentTimeMillis();
        long rtMs = jwtUtil.getExpiration(rt).getTime() - System.currentTimeMillis();
        assertThat(atMs).isBetween(ACCESS_MS - 5000, ACCESS_MS + 5000);
        assertThat(rtMs).isBetween(REFRESH_MS - 5000, REFRESH_MS + 5000);
        assertThat(rtMs).isGreaterThan(atMs);
    }

    @Test
    @DisplayName("同一毫秒相同 claims 生成的 token 互不相同（jti 唯一）")
    void sameClaims_tokensUnique() {
        String t1 = jwtUtil.createAccessToken(userClaims());
        String t2 = jwtUtil.createAccessToken(userClaims());
        assertThat(t1).isNotEqualTo(t2);
    }

    @Test
    @DisplayName("parseStringClaim / parseDeviceIdClaim 提取正确")
    void parseClaims_extractCorrect() {
        String token = jwtUtil.createAccessToken(userClaims());
        assertThat(jwtUtil.parseStringClaim(token)).isEqualTo("10001");
        assertThat(jwtUtil.parseDeviceIdClaim(token)).isEqualTo("7");
    }

    @Test
    @DisplayName("管理员 token：role claim 保留且可解析")
    void adminToken_rolePreserved() {
        String token = jwtUtil.createAccessToken(adminClaims());
        Map<String, Object> parsed = jwtUtil.parseToken(token);
        assertThat(parsed.get("role")).isEqualTo("SUPER_ADMIN");
    }

    @Test
    @DisplayName("过期 token：解析抛 ExpiredJwtException")
    void expiredToken_rejected() {
        String expired = jwtUtil.createToken(userClaims(), -1000L); // 已过期 1 秒
        assertThatThrownBy(() -> jwtUtil.parseToken(expired))
                .isInstanceOf(ExpiredJwtException.class);
        assertThatThrownBy(() -> jwtUtil.parseStringClaim(expired))
                .isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    @DisplayName("篡改签名：payload 已改 → 解析失败（签名校验拦截）")
    void tamperedToken_rejected() {
        String token = jwtUtil.createAccessToken(userClaims());
        // 篡改 payload 区（中间段）最后一个字符
        String[] parts = token.split("\\.");
        String tamperedPayload = parts[1].substring(0, parts[1].length() - 1)
                + (parts[1].endsWith("A") ? "B" : "A");
        String forged = parts[0] + "." + tamperedPayload + "." + parts[2];

        assertThatThrownBy(() -> jwtUtil.parseToken(forged))
                .isInstanceOf(JwtException.class);
    }

    @Test
    @DisplayName("空 token / 垃圾字符串：解析失败")
    void invalidToken_rejected() {
        assertThatThrownBy(() -> jwtUtil.parseToken(""))
                .isInstanceOf(IllegalArgumentException.class); // 空串：CharSequence cannot be null or empty
        assertThatThrownBy(() -> jwtUtil.parseToken("not.a.jwt"))
                .isInstanceOf(JwtException.class);             // 格式非法：解析阶段抛 JwtException
        assertThatThrownBy(() -> jwtUtil.parseToken(null))
                .isInstanceOf(IllegalArgumentException.class); // null：同样被 hasText 拒绝
    }

    @Test
    @DisplayName("getExpiration：返回精确的过期时间点")
    void getExpiration_accurate() {
        String token = jwtUtil.createAccessToken(userClaims());
        Date exp = jwtUtil.getExpiration(token);
        long delta = Math.abs(exp.getTime() - (System.currentTimeMillis() + ACCESS_MS));
        assertThat(delta).isLessThan(5000);
    }

    @Test
    @DisplayName("跨密钥解析：用不同密钥签发的 token 无法解析")
    void wrongKey_rejected() throws Exception {
        JwtProperties other = new JwtProperties();
        other.setSecret("another-secret-key-for-unit-test-0123456789abcdefghij");
        other.setAccessExpiration(ACCESS_MS);
        other.setRefreshExpiration(REFRESH_MS);
        JwtUtil otherUtil = new JwtUtil();
        Field f = JwtUtil.class.getDeclaredField("jwtProperties");
        f.setAccessible(true);
        f.set(otherUtil, other);

        String token = otherUtil.createAccessToken(userClaims());
        assertThatThrownBy(() -> jwtUtil.parseToken(token))
                .isInstanceOf(JwtException.class);
    }
}
