package com.rambo.module.goods.server.service.impl;

import lombok.extern.slf4j.Slf4j;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.common.constants.MessageConstants;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.context.IdHolder;
import com.rambo.module.goods.pojo.dto.GoodsEvaluationDTO;
import com.rambo.module.goods.pojo.entity.GoodsEvaluation;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.pojo.vo.GoodsEvaluationVO;
import com.rambo.module.goods.server.mapper.GoodsEvaluationMapper;
import com.rambo.module.goods.server.service.GoodsEvaluationService;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.notification.pojo.entity.Notification;
import jakarta.annotation.Resource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
public class GoodsEvaluationServiceImpl extends ServiceImpl<GoodsEvaluationMapper, GoodsEvaluation> implements GoodsEvaluationService {

    @Resource
    private GoodsOrderService orderService;
    @Resource
    private NotificationSender notificationSender;

    /**
     * 新增评价
     * @param evaluationDTO 评价DTO
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.EVALUATION,
            targetIdEL = "#evaluationDTO.getOrderId()",
            action = OperationActionEnum.GOODS_EVALUATION_ADD, descriptionEL = "'发布商品评价 orderId=' + #evaluationDTO.getOrderId()")
    public void addEvaluation(GoodsEvaluationDTO evaluationDTO) {
        Long fromUserId = IdHolder.getId();

        // 1. 校验订单存在且已完成
        GoodsOrder order = orderService.getById(evaluationDTO.getOrderId());
        if (order == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
        }
        if (order.getOrderStatus() != GoodsOrderStatus.COMPLETED) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_COMPLETED);
        }

        // 2. 校验当前用户是否为订单参与者
        boolean isBuyer = order.getBuyerId().equals(fromUserId);
        boolean isSeller = order.getOwnerId().equals(fromUserId);
        if (!isBuyer && !isSeller) {
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_TO_USER_ERROR);
        }

        // 3. 校验被评价人是否正确（买家评价卖家，卖家评价买家）
        if (isBuyer && !evaluationDTO.getToUserId().equals(order.getOwnerId())) {
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_TO_USER_ERROR);
        }
        if (isSeller && !evaluationDTO.getToUserId().equals(order.getBuyerId())) {
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_TO_USER_ERROR);
        }

        // 4. 校验是否已经评价过（防止重复评价；预检为快速路径，并发原子性由唯一索引 + DuplicateKeyException 兜底）
        long count = lambdaQuery()
                .eq(GoodsEvaluation::getOrderId, evaluationDTO.getOrderId())
                .eq(GoodsEvaluation::getFromUid, fromUserId)
                .count();
        if (count > 0) {
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_EXISTED);
        }

        // 5. 校验评分范围（1-5）
        if (evaluationDTO.getScore() == null || evaluationDTO.getScore() < 1 || evaluationDTO.getScore() > 5) {
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_SCORE_ERROR);
        }

        // 6. 保存评价
        GoodsEvaluation evaluation = new GoodsEvaluation();
        evaluation.setOrderId(evaluationDTO.getOrderId());
        evaluation.setFromUid(fromUserId);
        evaluation.setToUid(evaluationDTO.getToUserId());
        evaluation.setScore(evaluationDTO.getScore());
        evaluation.setContent(evaluationDTO.getContent());
        try {
            save(evaluation);
        } catch (DuplicateKeyException e) {
            // 并发窗口内对方已评价，幂等拒绝
            throw new BusinessException(MessageConstants.GOODS_EVALUATION_EXISTED);
        }

        // 7. 通知评价人
        notificationSender.sendAsync(NotificationMessage.builder()
                .userId(evaluationDTO.getToUserId())
                .type(NotificationType.GOODS_EVALUATE)
                .content(MessageConstants.GOODS_EVALUATION_SUCCESS)
                .refId(evaluation.getId())
                .build());
    }

    /**
     * 根据订单ID查看双方评价
     * @param orderId 订单ID
     * @return 包含买家评价和卖家评价的VO
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.EVALUATION,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_EVALUATION_VIEW, descriptionEL = "'查看商品评价 orderId=' + #orderId")
    public GoodsEvaluationVO getEvaluationsByOrderId(Long orderId) {
        log.info("查看商品评价 orderId={}", orderId);
        // 校验订单存在
        GoodsOrder order = orderService.getById(orderId);
        if (order == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
        }

        // 越权防护（IDOR）：仅订单参与方（买家/卖家）可查看双方评价，防止遍历 orderId 窃取他人评价
        Long currentUserId = IdHolder.getId();
        if (!order.getBuyerId().equals(currentUserId) && !order.getOwnerId().equals(currentUserId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 查询买家对卖家的评价（fromUid = buyerId, toUid = ownerId）
        GoodsEvaluation buyerToSeller = lambdaQuery()
                .eq(GoodsEvaluation::getOrderId, orderId)
                .eq(GoodsEvaluation::getFromUid, order.getBuyerId())
                .eq(GoodsEvaluation::getToUid, order.getOwnerId())
                .one();

        // 查询卖家对买家的评价（fromUid = ownerId, toUid = buyerId）
        GoodsEvaluation sellerToBuyer = lambdaQuery()
                .eq(GoodsEvaluation::getOrderId, orderId)
                .eq(GoodsEvaluation::getFromUid, order.getOwnerId())
                .eq(GoodsEvaluation::getToUid, order.getBuyerId())
                .one();

        return new GoodsEvaluationVO(buyerToSeller, sellerToBuyer);
    }
}
