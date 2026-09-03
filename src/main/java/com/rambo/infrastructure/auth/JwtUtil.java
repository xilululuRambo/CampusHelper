package com.rambo.infrastructure.auth;


import com.rambo.infrastructure.config.JwtProperties;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.Map;
import java.util.UUID;

@Component
public class JwtUtil {
    @Resource
    private JwtProperties jwtProperties;

    /**
     * 生成 Access Token（短效）
     */
    public String createAccessToken(Map<String, Object> claims) {
        return createToken(claims, jwtProperties.getAccessExpiration());
    }

    /**
     * 生成 Refresh Token（长效）
     */
    public String createRefreshToken(Map<String, Object> claims) {
        return createToken(claims, jwtProperties.getRefreshExpiration());
    }

    /**
     * 创建JWT token
     * @param claims 载荷信息
     * @return token
     */
    public String createToken(Map<String, Object> claims, long expiration) {
        // 将字符串密钥转换为 SecretKey 对象
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));

        //返回token
        return Jwts.builder()
                .claims(claims)
                // jti（JWT ID）：每次生成唯一。exp 是秒级精度，同秒内相同 claims 会生成完全相同的 token，
                // 导致 logout 拉黑后同一秒内重新登录拿到被拉黑的 token（新会话不可用）；加 jti 根治
                .id(UUID.randomUUID().toString())
                .expiration(new Date(System.currentTimeMillis() + expiration))
                .signWith(key)
                .compact();
    }

    /**
     * 解析token，获取载荷Claims
     */
    public Map<String, Object> parseToken(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        // 解析 + 自动校验：签名是否合法、是否过期
        return Jwts.parser()
                .verifyWith(key)      // 用同一个密钥校验签名
                .build()
                .parseSignedClaims(token)
                .getPayload();         // 获取自定义载荷信息
    }

    /**
     * 获取token中的用户ID
     */
    public String parseStringClaim(String token) {
        return (String) parseToken(token).get("id");
    }

    /**
     * 获取token中的设备ID
     */
    public String parseDeviceIdClaim(String token) {
        return (String) parseToken(token).get("deviceId");
    }

    /**
     * 获取token的过期时间
     * 注意：payload 中标准声明 exp 是原始数字类型，不能 (Date) 强转，
     * 必须用 jjwt 的 Claims.getExpiration()。
     */
    public Date getExpiration(String token) {
        SecretKey key = Keys.hmacShaKeyFor(jwtProperties.getSecret().getBytes(StandardCharsets.UTF_8));
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload()
                .getExpiration();
    }
}
