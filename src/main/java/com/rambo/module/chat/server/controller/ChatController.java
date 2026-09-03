package com.rambo.module.chat.server.controller;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.chat.pojo.dto.ChatMessageRequest;
import com.rambo.module.chat.pojo.entity.ChatMessage;
import com.rambo.infrastructure.websocket.WsMessenger;
import com.rambo.module.chat.server.service.ChatService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.stereotype.Controller;


import java.security.Principal;

@Controller
@Slf4j
@Tag(name = "websocket消息接口")
public class ChatController {
    @Resource
    private ChatService chatService;

    @Resource
    private WsMessenger wsMessenger;

    @MessageMapping("/chat.send")
    public void sendMessage(ChatMessageRequest request, Principal principal) {
        // 从Principal中获取当前登录用户的ID
        Long senderId = Long.valueOf(principal.getName());

        // 服务端推导接收者：不信任客户端传入的 receiverId，防止向非会话参与方越权投递
        Long receiverId = chatService.getOtherParticipant(request.getSessionId(), senderId);
        // 双保险：getOtherParticipant 已保证非空（任务未承接/会话非法时抛业务异常），
        // 此处再兜底一次，避免任何路径把消息推到 "null" 用户
        if (receiverId == null) {
            throw new BusinessException(MessageConstants.CHAT_RECEIVER_NOT_FOUND);
        }

        // 构建消息实体
        ChatMessage msg = new ChatMessage();
        msg.setSessionId(request.getSessionId());
        msg.setSenderId(senderId);
        msg.setReceiverId(receiverId);
        msg.setContent(request.getContent());
        msg.setMsgType(request.getMsgType());

        // 持久化
        ChatMessage chatMessage = chatService.saveMessage(msg);

        // 实时推送给接收者
        wsMessenger.sendToUser(receiverId, "/queue/chat", chatMessage);
    }
}
