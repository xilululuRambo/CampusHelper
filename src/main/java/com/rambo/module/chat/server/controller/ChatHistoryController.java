package com.rambo.module.chat.server.controller;

import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.chat.pojo.entity.ChatMessage;
import com.rambo.module.chat.server.service.ChatService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/chat")
@Slf4j
@Tag(name = "聊天消息接口")
public class ChatHistoryController {
    @Resource
    private ChatService chatService;

    /**
     * 分页查询历史消息（按发送时间倒序）
     *
     * @param sessionId 会话ID
     * @param pageNum   页码（从1开始，默认1）
     * @param pageSize  每页条数（默认20，上限100）
     * @return 分页结果
     */
    @GetMapping("/messages/{sessionId}")
    public Result<PageResult<ChatMessage>> getMessages(@PathVariable String sessionId,
                                                       @RequestParam(defaultValue = "1") int pageNum,
                                                       @RequestParam(defaultValue = "20") int pageSize) {
        log.info("查询历史消息，会话ID：{}，pageNum：{}，pageSize：{}", sessionId, pageNum, pageSize);
        return Result.success(chatService.getMessages(sessionId, pageNum, pageSize));
    }

    /**
     * 消息标为已读
     *
     * @param sessionId 会话ID
     * @return 成功结果
     */
    @PutMapping("/messages/{sessionId}/read")
    public Result<Void> markRead(@PathVariable String sessionId) {
        log.info("标记消息已读，会话ID：{}", sessionId);
        chatService.markRead(sessionId);
        return Result.success();
    }
}