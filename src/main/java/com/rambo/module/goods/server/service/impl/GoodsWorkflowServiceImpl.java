package com.rambo.module.goods.server.service.impl;

import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.common.context.IdHolder;
import com.rambo.infrastructure.database.TransactionUtils;
import com.rambo.module.chat.server.service.ChatSessionService;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsService;
import com.rambo.module.goods.server.service.GoodsWorkflowService;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import com.rambo.infrastructure.cache.LockClient;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GoodsWorkflowServiceImpl implements GoodsWorkflowService {
    @Resource
    private GoodsOrderService goodsOrderService;
    @Resource
    private GoodsService goodsService;
    @Resource
    private LockClient lockClient;
    @Resource
    private NotificationSender notificationSender;
    @Resource
    private ChatSessionService chatSessionService;
    @Resource
    private UserService userService;
    @Resource
    private GoodsEsSyncService goodsEsSyncService;

    /**
     * 确认收货订单完成
     *
     * @param orderId 订单ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_CONFIRM, descriptionEL = "'确认收货商品订单 orderId=' + #orderId")
    public void confirmOrder(Long orderId) {
        // 校验订单是否存在
        GoodsOrder goodsOrder = goodsOrderService.getById(orderId);
        if (goodsOrder == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
        }

        // 校验是否为买家确认收货
        Long userId = IdHolder.getId();
        if (!goodsOrder.getBuyerId().equals(userId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 校验订单状态是否为待收货
        if (goodsOrder.getOrderStatus() != GoodsOrderStatus.PENDING_CONFIRM) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_STATUS_ERROR);
        }

        // 更新订单状态为已完成
        boolean isSuccess = goodsOrderService.lambdaUpdate()
                .eq(GoodsOrder::getId, orderId)
                .eq(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_CONFIRM)
                .set(GoodsOrder::getOrderStatus, GoodsOrderStatus.COMPLETED)
                .set(GoodsOrder::getConfirmTime, LocalDateTime.now())
                .update();
        if (!isSuccess) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_CONFIRM_ERROR);
        }

        // 更新商品状态为已出售
        isSuccess = goodsService.lambdaUpdate()
                .eq(Goods::getId, goodsOrder.getGoodsId())
                .eq(Goods::getStatus, GoodsStatus.TRADING)
                .set(Goods::getStatus, GoodsStatus.SOLD_OUT)
                .update();
        if (!isSuccess) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_CONFIRM_ERROR);
        }
        // 商品状态已变更（CAS 直改绕过 @CacheEvict 业务方法）：显式失效详情缓存，防止残留旧状态
        goodsService.evictGoodsDetail(goodsOrder.getGoodsId());
        // 商品状态已变更（TRADING -> SOLD_OUT）：事务内登记 ES 同步意图，已售出的商品不应再出现在搜索结果
        goodsEsSyncService.syncToEsAsync(goodsOrder.getGoodsId());

        // 发送订单确认收货成功消息
        // 1. 通知买家订单已完成
        notificationSender.sendAsync(NotificationMessage.builder()
                .userId(goodsOrder.getBuyerId())
                .type(NotificationType.TRADE_COMPLETE)
                .content(MessageConstants.GOODS_ORDER_COMPLETE_SUCCESS)
                .refId(orderId)
                .build());

        // 2. 通知卖家订单已完成
        notificationSender.sendAsync(NotificationMessage.builder()
                .userId(goodsOrder.getOwnerId())
                .type(NotificationType.TRADE_COMPLETE)
                .content(MessageConstants.GOODS_ORDER_COMPLETE_SUCCESS)
                .refId(orderId)
                .build());

        //结束会话（事务提交后关闭，回滚则会话保持打开）
        String sessionId = "goods_" + orderId;
        TransactionUtils.afterCommit(() -> chatSessionService.closeSession(sessionId));
    }

    /**
     * 取消订单
     *
     * @param orderId 订单ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_CANCEL, descriptionEL = "'取消商品订单 orderId=' + #orderId")
    public void cancelOrder(Long orderId) {
        Long userId = IdHolder.getId();
        String lockKey = PrefixConstants.GOODS_ORDER_LOCK_PREFIX + orderId;
        boolean locked = false;
        try {
            if (!lockClient.tryLock(lockKey, NumConstants.LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;
            // 1. 校验订单是否存在
            GoodsOrder goodsOrder = goodsOrderService.getById(orderId);
            if (goodsOrder == null) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
            }

            // 2. 校验是否为买家或卖家取消
            if (!goodsOrder.getBuyerId().equals(userId) && !goodsOrder.getOwnerId().equals(userId)) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            // 3. 校验订单状态是否为待付款
            if (goodsOrder.getOrderStatus() != GoodsOrderStatus.PENDING_PAYMENT) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_STATUS_ERROR);
            }

            // 4. 更新订单状态为已取消
            boolean isSuccess = goodsOrderService.lambdaUpdate()
                    .eq(GoodsOrder::getId, orderId)
                    .eq(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_PAYMENT)
                    .set(GoodsOrder::getOrderStatus, GoodsOrderStatus.CANCELLED)
                    .update();
            if (!isSuccess) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_CANCEL_ERROR);
            }

            // 恢复商品状态：仅当商品处于交易中且未被管理员强制下架时才恢复在售
            // 管理员下架的商品状态为 DISABLED（adminDisabled=1），此处匹配不到则跳过恢复，不阻塞订单取消
            Goods goods = goodsService.getById(goodsOrder.getGoodsId());
            if (goods != null && goods.getStatus() == GoodsStatus.TRADING && !Boolean.TRUE.equals(goods.getAdminDisabled())) {
                goods.setStatus(GoodsStatus.NORMAL);
                boolean updated = goodsService.updateById(goods);
                if (!updated) {
                    throw new BusinessException(MessageConstants.GOODS_ORDER_CANCEL_ERROR);
                }
                // 商品状态已变更（TRADING -> NORMAL）：事务内登记 ES 同步意图，取消后应重新可被搜到
                goodsEsSyncService.syncToEsAsync(goods);
            }

            // 结束会话（订单取消，买卖双方沟通终止；事务提交后关闭，回滚则会话保持打开）
            TransactionUtils.afterCommit(() -> chatSessionService.closeSession("goods_" + orderId));
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 管理员强制取消订单（已付款订单执行退款：扣回卖家余额 + 退回买家余额）
     *
     * @param orderId 订单ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_CANCEL_BY_ADMIN, descriptionEL = "'管理员强制取消商品订单 orderId=' + #orderId")
    public void cancelOrderByAdmin(Long orderId) {
        String lockKey = PrefixConstants.GOODS_ORDER_LOCK_PREFIX + orderId;
        boolean locked = false;
        try {
            if (!lockClient.tryLock(lockKey, NumConstants.LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;
            // 1. 校验订单是否存在
            GoodsOrder goodsOrder = goodsOrderService.getById(orderId);
            if (goodsOrder == null) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
            }

            // 2. 校验订单状态为未完成（待付款/待发货/待收货），已完成/已取消不可操作
            if (goodsOrder.getOrderStatus() == GoodsOrderStatus.COMPLETED
                    || goodsOrder.getOrderStatus() == GoodsOrderStatus.CANCELLED) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_STATUS_ERROR);
            }
            boolean paid = goodsOrder.getOrderStatus() != GoodsOrderStatus.PENDING_PAYMENT;

            // 3. 已付款订单退款：扣回卖家余额 → 退回买家余额（原子操作 + 余额不足校验，防重复退款）
            if (paid) {
                boolean deducted = userService.lambdaUpdate()
                        .eq(User::getId, goodsOrder.getOwnerId())
                        .ge(User::getBalance, goodsOrder.getTotalAmount())
                        .setSql("balance = balance - {0}", goodsOrder.getTotalAmount())
                        .update();
                if (!deducted) {
                    throw new BusinessException(MessageConstants.GOODS_ORDER_REFUND_ERROR);
                }
                userService.lambdaUpdate()
                        .eq(User::getId, goodsOrder.getBuyerId())
                        .setSql("balance = balance + {0}", goodsOrder.getTotalAmount())
                        .update();
            }

            // 4. 更新订单状态为已取消（条件更新兜底幂等：0 行说明状态已被并发修改，回滚）
            boolean isSuccess = goodsOrderService.lambdaUpdate()
                    .eq(GoodsOrder::getId, orderId)
                    .eq(GoodsOrder::getOrderStatus, goodsOrder.getOrderStatus())
                    .set(GoodsOrder::getOrderStatus, GoodsOrderStatus.CANCELLED)
                    .update();
            if (!isSuccess) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_CANCEL_ERROR);
            }

            // 5. 通知买卖双方
            if (paid) {
                notificationSender.sendAsync(NotificationMessage.builder()
                        .userId(goodsOrder.getBuyerId())
                        .type(NotificationType.GOODS_ORDER_CANCEL)
                        .content(MessageConstants.BUYER_GOODS_ORDER_CANCEL_REFUND)
                        .refId(orderId)
                        .build());
                notificationSender.sendAsync(NotificationMessage.builder()
                        .userId(goodsOrder.getOwnerId())
                        .type(NotificationType.GOODS_ORDER_CANCEL)
                        .content(MessageConstants.OWNER_GOODS_ORDER_CANCEL_REFUND)
                        .refId(orderId)
                        .build());
            } else {
                notificationSender.sendAsync(NotificationMessage.builder()
                        .userId(goodsOrder.getBuyerId())
                        .type(NotificationType.GOODS_ORDER_CANCEL)
                        .content(MessageConstants.BUYER_GOODS_ORDER_CANCEL)
                        .refId(orderId)
                        .build());
                notificationSender.sendAsync(NotificationMessage.builder()
                        .userId(goodsOrder.getOwnerId())
                        .type(NotificationType.GOODS_ORDER_CANCEL)
                        .content(MessageConstants.OWNER_GOODS_ORDER_CANCEL)
                        .refId(orderId)
                        .build());
            }

            // 结束会话（事务提交后关闭，回滚则会话保持打开）
            TransactionUtils.afterCommit(() -> chatSessionService.closeSession("goods_" + orderId));
        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }
}