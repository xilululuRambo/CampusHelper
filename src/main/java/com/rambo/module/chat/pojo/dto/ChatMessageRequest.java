package com.rambo.module.chat.pojo.dto;

import com.rambo.module.chat.enums.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatMessageRequest {
    @NotNull
    @Schema(description = "会话ID")
    private String sessionId;

    /**
     * @deprecated 接收者由服务端按会话参与方推导（见 ChatService#getOtherParticipant），
     * 客户端传入的 receiverId 会被忽略。保留字段仅为兼容旧客户端，新客户端无需传值。
     */
    @Deprecated
    @Schema(description = "接收者ID（已废弃，服务端按会话推导，传值将被忽略）")
    private Long receiverId;

    @NotNull
    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "消息类型")
    private MessageType msgType = MessageType.TEXT;
}