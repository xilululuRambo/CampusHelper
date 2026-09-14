package com.rambo.infrastructure.websocket;

import com.rambo.infrastructure.auth.ChatAuthInterceptor;
import com.rambo.infrastructure.auth.WebSocketAuthInterceptor;
import jakarta.annotation.Resource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.simp.config.ChannelRegistration;
import org.springframework.messaging.simp.config.MessageBrokerRegistry;
import org.springframework.web.socket.config.annotation.EnableWebSocketMessageBroker;
import org.springframework.web.socket.config.annotation.StompEndpointRegistry;
import org.springframework.web.socket.config.annotation.WebSocketMessageBrokerConfigurer;

import java.util.List;

/**
 * WebSocket / STOMP 配置。
 *
 * <p><b>建连端点：</b>{@code /ws}（带 SockJS 兜底），Origin 白名单来自 {@link WebSocketProperties#getAllowedOrigins()}。
 * dev 默认 {@code *} 方便本地多端口调试；prod 必须显式列出前端域名以防 CSWSH。</p>
 *
 * <p><b>入站拦截器：</b>{@link WebSocketAuthInterceptor}（CONNECT 鉴权 + 黑名单/禁用双查）
 * → {@link ChatAuthInterceptor}（SEND/SUBSCRIBE 兜底：必须来自已认证连接）。</p>
 *
 * <p><b>消息代理：</b>外部 STOMP broker（RabbitMQ STOMP 插件，端口 61613）。
 * 切换原因：项目原有 {@code enableSimpleBroker} 仅在单 JVM 内广播，
 * 多实例部署时用户连在 A、消息从 B 推则收不到。改用外部 broker 后所有实例订阅同一频道，消息可跨实例路由。</p>
 *
 * <p><b>运行时要求：</b>RabbitMQ 容器需 {@code rabbitmq-plugins enable rabbitmq_stomp}；
 * {@code relayHost/Port/Client/System} 用户必须在 RabbitMQ 中预先创建。</p>
 */
@Configuration
@EnableWebSocketMessageBroker
@EnableConfigurationProperties(WebSocketProperties.class)
public class WebSocketConfig implements WebSocketMessageBrokerConfigurer {

    @Resource
    private ChatAuthInterceptor chatAuthInterceptor;

    @Resource
    private WebSocketAuthInterceptor webSocketAuthInterceptor;

    @Resource
    private WebSocketProperties webSocketProperties;

    /**
     * 配置客户端入站通道拦截器
     */
    @Override
    public void configureClientInboundChannel(ChannelRegistration registration) {
        registration.interceptors(webSocketAuthInterceptor, chatAuthInterceptor);
    }

    /**
     * 注册 WebSocket 连接端点。
     * <p>Origin 白名单由配置决定（dev=*，prod=前端真实域名列表），避免硬编码与跨站劫持风险。</p>
     */
    @Override
    public void registerStompEndpoints(StompEndpointRegistry registry) {
        List<String> origins = webSocketProperties.getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            // 空列表 → 拒绝所有跨域建连（仅同源直连可用；生产未配置是显式 fail-safe）
            registry.addEndpoint("/ws").setAllowedOriginPatterns().withSockJS();
        } else {
            registry.addEndpoint("/ws").setAllowedOriginPatterns(origins.toArray(new String[0])).withSockJS();
        }
    }

    /**
     * 配置消息代理：按 {@code app.websocket.broker-type} 切换。
     * <p>{@code relay}：使用外部 STOMP broker（RabbitMQ STOMP 插件，端口 61613）。
     * 所有实例订阅同一组 exchange/topic，消息可跨实例路由；
     * client 与 system 账号分离：client 处理业务消息，system 处理心跳/订阅事件等控制平面消息。</p>
     * <p>{@code simple}：单实例内存 broker（测试/单机环境，避免依赖 RabbitMQ STOMP 插件与 Reactor Netty）。</p>
     */
    @Override
    public void configureMessageBroker(MessageBrokerRegistry registry) {
        if ("simple".equalsIgnoreCase(webSocketProperties.getBrokerType())) {
            registry.enableSimpleBroker("/topic", "/queue");
        } else {
            registry.enableStompBrokerRelay("/topic", "/queue")
                    .setRelayHost(webSocketProperties.getRelayHost())
                    .setRelayPort(webSocketProperties.getRelayPort())
                    .setClientLogin(webSocketProperties.getClientLogin())
                    .setClientPasscode(webSocketProperties.getClientPasscode())
                    .setSystemLogin(webSocketProperties.getSystemLogin())
                    .setSystemPasscode(webSocketProperties.getSystemPasscode())
                    .setUserDestinationBroadcast("/topic/unresolved-user-destination")
                    .setUserRegistryBroadcast("/topic/user-registry-broadcast");
        }
        registry.setApplicationDestinationPrefixes("/app");
    }
}
