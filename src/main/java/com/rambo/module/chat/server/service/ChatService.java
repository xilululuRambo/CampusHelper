package com.rambo.module.chat.server.service;

import com.rambo.common.result.PageResult;
import com.rambo.module.chat.pojo.entity.ChatMessage;

public interface ChatService{
    /**
     * 保存消息
     * @param msg 消息实体
     * @return 保存后的消息实体
     */
    ChatMessage saveMessage(ChatMessage msg);  // 存消息

    /**
     * 分页查询历史消息（按发送时间倒序，避免长会话全量加载）
     *
     * @param sessionId 会话ID
     * @param pageNum   页码（从1开始）
     * @param pageSize  每页条数
     * @return 分页结果
     */
    PageResult<ChatMessage> getMessages(String sessionId, int pageNum, int pageSize);        // 查历史消息

    /**
     * 标记消息已读
     * @param sessionId 会话ID
     */
    void markRead(String sessionId);

    /**
     * 获取会话中的另一方参与人（服务端推导，不信任客户端传入的接收者）
     *
     * @param sessionId 会话ID
     * @param senderId  当前发送者ID
     * @return 会话另一方参与人ID
     */
    Long getOtherParticipant(String sessionId, Long senderId);
}
