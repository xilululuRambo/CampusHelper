package com.rambo.module.chat.pojo.entity;

import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.module.chat.enums.MessageType;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.LocalDateTime;

@Data
@Document(collection = "chat_message")
@CompoundIndexes({
        // 历史消息查询：按会话过滤 + 按发送时间倒序排序（getMessages 全量扫的索引支撑）
        @CompoundIndex(def = "{'sessionId': 1, 'createTime': -1}", name = "idx_session_create_time"),
        // 未读标记：markRead 按会话 + 接收者 + 未读状态批量更新
        @CompoundIndex(def = "{'sessionId': 1, 'receiverId': 1, 'isRead': 1}", name = "idx_session_receiver_read")
})
public class ChatMessage {
    @Id
    @Schema(description = "消息ID")
    private String id;

    @Schema(description = "会话ID")
    private String sessionId;

    @Schema(description = "发送者ID")
    private Long senderId;

    @Schema(description = "接收者ID")
    private Long receiverId;

    @Schema(description = "消息内容")
    private String content;

    @Schema(description = "消息类型,0-文本,1-图片")
    private MessageType msgType;

    @Schema(description = "消息状态,0-未读,1-已读")
    private IsReadStatus isRead;

    @Schema(description = "发送时间")
    private LocalDateTime createTime;
}