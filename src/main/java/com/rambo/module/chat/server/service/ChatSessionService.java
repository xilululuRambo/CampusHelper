package com.rambo.module.chat.server.service;

public interface ChatSessionService {
    /**
     * 创建聊天会话（如果不存在）
     * @param sessionId 会话ID
     * @param publisherId 任务发布者ID
     * @param applicantId 任务申请人ID
     */
    void createIfNotExist(String sessionId, Long publisherId, Long applicantId);

    /**
     * 结束会话
     * @param sessionId 会话ID
     */
    void closeSession(String sessionId);
}
