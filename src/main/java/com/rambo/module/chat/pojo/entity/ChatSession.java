package com.rambo.module.chat.pojo.entity;

import com.rambo.module.chat.enums.SessionStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "chat_session")
public class ChatSession {
    @Id
    @Schema(description = "会话ID")
    private String sessionId;

    @Indexed
    @Schema(description = "用户AID")
    private Long userAId;

    @Indexed
    @Schema(description = "用户BID")
    private Long userBId;

    @Schema(description = "会话状态")
    private SessionStatus status;

    @Schema(description = "关闭时间")
    private LocalDateTime closeTime;
}
