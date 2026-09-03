package com.rambo.module.chat.server.service.impl;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.module.notification.enums.IsReadStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.context.IdHolder;
import com.rambo.common.result.PageResult;
import com.rambo.module.chat.pojo.entity.ChatMessage;
import com.rambo.module.chat.server.service.ChatService;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.server.service.TaskService;
import jakarta.annotation.Resource;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class ChatServiceImpl implements ChatService {
    @Resource
    private MongoTemplate mongoTemplate;

    @Resource
    private TaskService taskService;

    @Resource
    private GoodsOrderService goodsOrderService;

    /**
     * 保存消息
     *
     * @param msg 消息实体
     * @return 保存后的消息实体
     */
    @Override
    public ChatMessage saveMessage(ChatMessage msg) {
        msg.setCreateTime(LocalDateTime.now());
        msg.setIsRead(IsReadStatus.UNREAD);
        return mongoTemplate.save(msg);
    }

    /**
     * 分页查询历史消息（按发送时间倒序）
     *
     * @param sessionId 会话ID
     * @param pageNum   页码（从1开始）
     * @param pageSize  每页条数
     * @return 分页结果
     */
    @Override
    public PageResult<ChatMessage> getMessages(String sessionId, int pageNum, int pageSize) {
        Long userId = IdHolder.getId();
        // 按会话类型分流校验访问权限：任务会话校验发布者/承接者，商品会话校验买家/卖家
        if (sessionId.startsWith("task_")) {
            Long taskId = Long.parseLong(sessionId.substring(5));
            if (!taskService.isParticipant(taskId, userId)) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }
        } else if (sessionId.startsWith("goods_")) {
            Long orderId = Long.parseLong(sessionId.substring(6));
            if (!goodsOrderService.isParticipant(orderId, userId)) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }
        } else {
            // 未知会话类型，拒绝访问
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }
        // 分页参数防御：页码/每页条数下限 1，每页条数上限防深分页
        int safePageNum = Math.max(pageNum, 1);
        int safePageSize = Math.min(Math.max(pageSize, 1), NumConstants.CHAT_HISTORY_PAGE_SIZE_LIMIT);
        Criteria sessionCriteria = Criteria.where("sessionId").is(sessionId);
        // 复合索引 idx_session_create_time 覆盖：sessionId 过滤 + createTime 倒序排序
        Query query = Query.query(sessionCriteria)
                .with(Sort.by(Sort.Direction.DESC, "createTime"))
                .skip((long) (safePageNum - 1) * safePageSize)
                .limit(safePageSize);
        List<ChatMessage> messages = mongoTemplate.find(query, ChatMessage.class);
        long total = mongoTemplate.count(Query.query(sessionCriteria), ChatMessage.class);
        return new PageResult<>(total, messages);
    }

    /**
     * 标记消息已读
     *
     * @param sessionId 会话ID
     */
    @Override
    public void markRead(String sessionId) {
        Long userId = IdHolder.getId();
        Query query = Query
                .query(Criteria.where("sessionId").is(sessionId)
                        .and("receiverId").is(userId)
                        .and("isRead").is(IsReadStatus.UNREAD));
        Update update = Update.update("isRead", IsReadStatus.READ);
        mongoTemplate.updateMulti(query, update, ChatMessage.class);
    }

    /**
     * 获取会话中的另一方参与人
     * <p>
     * 按会话类型解析：任务会话返回发布者/承接者中非发送者的一方，商品会话返回买家/卖家中非发送者的一方
     *
     * @param sessionId 会话ID
     * @param senderId  当前发送者ID
     * @return 会话另一方参与人ID
     * @throws BusinessException 会话不存在或会话类型未知时抛出
     */
    @Override
    public Long getOtherParticipant(String sessionId, Long senderId) {
        if (sessionId.startsWith("task_")) {
            Long taskId = Long.parseLong(sessionId.substring(5));
            Task task = taskService.lambdaQuery().eq(Task::getId, taskId).one();
            if (task == null) {
                throw new BusinessException(MessageConstants.TASK_NOT_FOUND);
            }
            // 参与方校验：发送者必须为发布者或承接者，否则拒绝。
            // 若不校验，攻击者传任意 sessionId 会被推导出"另一方"ID，消息可越权投递到他人会话
            if (!senderId.equals(task.getPublisherId()) && !senderId.equals(task.getApplicantId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }
            Long otherId = senderId.equals(task.getPublisherId()) ? task.getApplicantId() : task.getPublisherId();
            // 任务未被承接时 applicantId 为 null：若直接返回 null，上层会推送到字面量 "null" 用户导致消息静默丢失，
            // 这里显式拒绝并给出明确错误
            if (otherId == null) {
                throw new BusinessException(MessageConstants.TASK_NOT_ACCEPTED);
            }
            return otherId;
        } else if (sessionId.startsWith("goods_")) {
            Long orderId = Long.parseLong(sessionId.substring(6));
            GoodsOrder order = goodsOrderService.lambdaQuery().eq(GoodsOrder::getId, orderId).one();
            if (order == null) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
            }
            // 参与方校验：发送者必须为买家或卖家
            if (!senderId.equals(order.getBuyerId()) && !senderId.equals(order.getOwnerId())) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }
            return senderId.equals(order.getBuyerId()) ? order.getOwnerId() : order.getBuyerId();
        }
        throw new BusinessException(MessageConstants.NO_PERMISSION);
    }
}
