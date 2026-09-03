package com.rambo.infrastructure.auth;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.Resource;
import org.springframework.lang.NonNull;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.stereotype.Component;

/**
 * Chat 消息入站拦截器（兜底层）。
 *
 * <p>会话归属校验已下沉到 {@code ChatController -> ChatService.getOtherParticipant}，
 * 对消息体 payload 中的 sessionId 做参与方校验（拦截器只能读到 STOMP 帧原生 header，
 * 与 Controller 实际使用的 payload 不一致会形成错位鉴权，可被越权利用）。</p>
 *
 * <p>本拦截器仅兜底：SEND / SUBSCRIBE 必须来自已认证连接
 * （user 由 {@link WebSocketAuthInterceptor} 在 CONNECT 帧写入，未认证连接直接拒绝）。</p>
 *
 * <p><b>禁用复查：</b>CONNECT 帧只校验「建立连接那一刻」的状态；
 * 连接建立后账号被禁用，长连接仍可收发消息。这里在 SEND 帧复查
 * {@code user_disabled:{id}} 标记，被禁用用户的发送动作立即被阻断，
 * 堵住「CONNECT 后被禁用」的窗口期（仅 SEND 需要复查，SUBSCRIBE 不产生业务副作用）。</p>
 */
@Component
public class ChatAuthInterceptor implements ChannelInterceptor {

    @Resource
    private CacheClient cacheClient;

    @Override
    public Message<?> preSend(@NonNull Message<?> message, @NonNull MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor != null
                && (StompCommand.SEND.equals(accessor.getCommand()) || StompCommand.SUBSCRIBE.equals(accessor.getCommand()))) {
            if (accessor.getUser() == null || accessor.getUser().getName() == null) {
                throw new BusinessException(MessageConstants.UNAUTHORIZED);
            }
            // 连接期间被禁用：SEND 时复查禁用标记（hasKey O(1)，与 WebSocketAuthInterceptor 的 CONNECT 校验同源）
            if (StompCommand.SEND.equals(accessor.getCommand())
                    && Boolean.TRUE.equals(cacheClient.hasKey(PrefixConstants.USER_DISABLED + accessor.getUser().getName()))) {
                throw new BusinessException(MessageConstants.USER_DISABLED);
            }
        }
        return message;
    }
}
