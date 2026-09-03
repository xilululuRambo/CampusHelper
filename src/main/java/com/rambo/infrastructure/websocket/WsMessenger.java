package com.rambo.infrastructure.websocket;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/**
 * WebSocket 推送门面：业务层推送动作的唯一入口，屏蔽 SimpMessagingTemplate 与消息代理细节，
 * 业务模块不再感知 Spring Messaging 类型；换消息代理（如 STOMP→RSocket、单机→集群广播）时业务层零改动。
 *
 * <p>设计要点：
 * <ul>
 *   <li><b>异常语义透传</b>：本门面只做目的地封装与类型适配，不吞异常——推送方法抛出的异常
 *       原样透出，由调用方按业务决定静默/重试（与直连 SimpMessagingTemplate 行为完全一致）；</li>
 *   <li><b>懒加载注入</b>：SimpMessagingTemplate 初始化会触发 WebSocket 配置
 *       （webSocketConfig → chatAuthInterceptor → 业务 Service → 业务门面），直接注入会形成循环依赖；
 *       对模板采用 {@code @Lazy}，实际推送时才初始化底层代理，破除循环
 *       （继承 NotificationSenderImpl 既有方案）；门面自身不依赖任何业务 Service，业务层注入本门面无初始化顺序问题；</li>
 *   <li><b>目的地前缀透明</b>：目的地（如 {@code /queue/chat}、{@code /queue/notifications}）属于业务语义，
 *       由调用方显式传入，门面不做隐含拼接。</li>
 * </ul>
 */
@Slf4j
@Component
public class WsMessenger {

    @Resource
    @Lazy
    private SimpMessagingTemplate messagingTemplate;

    /**
     * 推送给指定用户（用户级目的地，如 {@code /queue/xxx}；user 参数为消息代理识别的用户标识）
     */
    public void sendToUser(String userId, String destination, Object payload) {
        messagingTemplate.convertAndSendToUser(userId, destination, payload);
    }

    /**
     * 推送给指定用户（Long 版本，内部转字符串，省去调用方 String.valueOf）
     */
    public void sendToUser(Long userId, String destination, Object payload) {
        sendToUser(String.valueOf(userId), destination, payload);
    }

    /**
     * 广播到全局目的地（如 {@code /topic/xxx}）
     */
    public void sendToAll(String destination, Object payload) {
        messagingTemplate.convertAndSend(destination, payload);
    }
}
