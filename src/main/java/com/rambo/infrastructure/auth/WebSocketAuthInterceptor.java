package com.rambo.infrastructure.auth;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.Resource;
import lombok.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.JwtException;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private CacheClient cacheClient;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        // 从 message 中获取 StompHeaderAccessor，用于解析 header
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);

        // 只在 CONNECT 帧进行认证
        if (accessor != null && StompCommand.CONNECT.equals(accessor.getCommand())) {
            // 从 header 中获取 token，如 "Authorization: Bearer xxx"
            String token = accessor.getFirstNativeHeader("Authorization");
            if (token == null || token.isEmpty()) {
                throw new BusinessException(MessageConstants.UNAUTHORIZED);
            }
            if (token.startsWith("Bearer ")) {
                token = token.substring(7);
            }
            // 校验 JWT 签名与有效期（解析失败即过期/伪造，统一拒绝建连）
            String userId;
            try {
                userId = jwtUtil.parseStringClaim(token);
            } catch (JwtException | IllegalArgumentException e) {
                throw new BusinessException(MessageConstants.UNAUTHORIZED);
            }
            // 与 HTTP 侧 JwtInterceptor 对齐的服务端状态校验：
            // 签名校验只能证明 token 未被篡改且未过期，
            // 无法识别「已登出拉黑」与「账号禁用」两类运行时状态
            if (Boolean.TRUE.equals(cacheClient.hasKey(PrefixConstants.AT_BLACKLIST + token))) {
                throw new BusinessException(MessageConstants.UNAUTHORIZED);
            }
            if (Boolean.TRUE.equals(cacheClient.hasKey(PrefixConstants.USER_DISABLED + userId))) {
                throw new BusinessException(MessageConstants.USER_DISABLED);
            }
            // 将用户信息放入 session，后续可通过 Principal 获取
            accessor.setUser(() -> userId);
        }
        return message;
    }
}
