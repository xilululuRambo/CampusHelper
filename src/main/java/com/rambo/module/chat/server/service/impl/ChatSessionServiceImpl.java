package com.rambo.module.chat.server.service.impl;

import com.rambo.common.constants.MessageConstants;
import com.rambo.module.chat.enums.SessionStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.chat.pojo.entity.ChatSession;
import com.rambo.module.chat.server.service.ChatSessionService;
import jakarta.annotation.Resource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class ChatSessionServiceImpl implements ChatSessionService {
    @Resource
    private MongoTemplate mongoTemplate;

    /**
     * 如果会话不存在，则创建新会话
     * @param sessionId 会话ID
     * @param publisherId 任务发布者ID
     * @param applicantId 任务申请人ID
     */
    @Override
    public void createIfNotExist(String sessionId, Long publisherId, Long applicantId) {
        // 检查会话是否存在
        Query query = new Query(Criteria
                .where("sessionId").is(sessionId)
                .and("userAId").is(publisherId)
                .and("userBId").is(applicantId)
                .and("status").is(SessionStatus.OPEN));
        ChatSession chatSession = mongoTemplate.findOne(query, ChatSession.class);
        if (chatSession == null) {
            // 会话不存在，创建新会话
            ChatSession newChatSession = new ChatSession();
            newChatSession.setSessionId(sessionId);
            newChatSession.setUserAId(publisherId);
            newChatSession.setUserBId(applicantId);
            newChatSession.setStatus(SessionStatus.OPEN);
            mongoTemplate.save(newChatSession);
        }
    }

    /**
     * 关闭会话
     * @param sessionId 会话ID
     */
    @Override
    public void closeSession(String sessionId) {
        // 检查会话是否存在
        Query query = new Query(Criteria
                .where("sessionId").is(sessionId)
                .and("status").is(SessionStatus.OPEN));
        ChatSession chatSession = mongoTemplate.findOne(query, ChatSession.class);
        if (chatSession == null) {
            throw new BusinessException(MessageConstants.SESSION_NOT_FOUND);
        }
        // 关闭会话
        chatSession.setStatus(SessionStatus.CLOSED);
        chatSession.setCloseTime(LocalDateTime.now());
        mongoTemplate.save(chatSession);
    }
}
