package com.rambo.infrastructure.auth;

/**
 * AccessToken ThreadLocal 持有者，用于在 logout 等场景获取当前请求的 accessToken
 */
public class AccessTokenHolder {

    private static final ThreadLocal<String> TOKEN_THREAD_LOCAL = new ThreadLocal<>();

    public static void setToken(String token) {
        TOKEN_THREAD_LOCAL.set(token);
    }

    public static String getToken() {
        return TOKEN_THREAD_LOCAL.get();
    }

    public static void clear() {
        TOKEN_THREAD_LOCAL.remove();
    }
}
