package com.rambo.helper;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 测试登录用户：注册+登录后得到的身份信息。
 * 所有接口测试统一用 AuthUser 传递身份，禁止在用例里散落手机号/token 字符串。
 */
@Data
@AllArgsConstructor
public class AuthUser {

    /** 随机生成的手机号（每次注册不同，保证用例间零依赖） */
    private final String phone;

    /** 登录后从 token 解析出的用户 ID */
    private final Long userId;

    /** Access Token，放入 Authorization: Bearer <at> 请求头 */
    private final String accessToken;

    /** Refresh Token（用于需要验证双令牌的场景） */
    private final String refreshToken;

    /** 设备 ID（RT 存于 user_tokens:{userId} Hash 的 field） */
    private final String deviceId;
}
