package com.rambo.module.goods.server.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.server.service.NotificationSender;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.common.context.IdHolder;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.module.goods.pojo.dto.GoodsOrderDTO;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.pojo.entity.OrderItem;
import com.rambo.module.goods.pojo.vo.GoodsOrderDetailVO;
import com.rambo.module.goods.pojo.vo.GoodsOrderListVO;
import com.rambo.module.goods.server.mapper.GoodsOrderMapper;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.OrderItemService;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import com.rambo.infrastructure.cache.LockClient;
import com.rambo.infrastructure.database.TransactionUtils;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class GoodsOrderServiceImpl extends ServiceImpl<GoodsOrderMapper, GoodsOrder> implements GoodsOrderService {

    @Resource
    private UserService userService;
    @Resource
    private LockClient lockClient;
    @Resource
    private OrderItemService orderItemService;
    @Resource
    private NotificationSender notificationSender;

    /**
     * 自动创建订单
     *
     * @param goodsOrderDTO 订单DTO
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "null",
            action = OperationActionEnum.GOODS_ORDER_CREATE, descriptionEL = "'生成商品订单'")
    public Long createOrder(GoodsOrderDTO goodsOrderDTO) {
        // 1. 基础非空校验
        if (goodsOrderDTO.getGoodsId() == null) {
            throw new BusinessException(MessageConstants.GOODS_ID_EMPTY);
        }
        if (goodsOrderDTO.getBuyerId() == null) {
            throw new BusinessException(MessageConstants.BUYER_ID_EMPTY);
        }
        if (goodsOrderDTO.getOwnerId() == null) {
            throw new BusinessException(MessageConstants.OWNER_ID_EMPTY);
        }
        if (goodsOrderDTO.getTotalAmount() == null || goodsOrderDTO.getTotalAmount() <= 0) {
            throw new BusinessException(MessageConstants.TOTAL_AMOUNT_EMPTY);
        }

        // 2. 安全校验
        if (goodsOrderDTO.getBuyerId().equals(goodsOrderDTO.getOwnerId())) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 创建订单
        GoodsOrder goodsOrder = new GoodsOrder();
        goodsOrder.setGoodsId(goodsOrderDTO.getGoodsId());
        goodsOrder.setOwnerId(goodsOrderDTO.getOwnerId());
        goodsOrder.setBuyerId(goodsOrderDTO.getBuyerId());
        goodsOrder.setTotalAmount(goodsOrderDTO.getTotalAmount());
        save(goodsOrder);
        return goodsOrder.getId();
    }

    /**
     * 付款
     *
     * @param orderId 订单ID
     */
    @Override
    @Transactional
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_PAY, descriptionEL = "'支付商品订单 orderId=' + #orderId")
    public void pay(Long orderId) {
        String lockKey = PrefixConstants.GOODS_ORDER_LOCK_PREFIX + orderId;
        boolean locked = false;
        try {
            if (!lockClient.tryLock(lockKey, NumConstants.LOCK_WAIT_TIME_SECONDS, TimeUnit.SECONDS)) {
                throw new BusinessException(MessageConstants.SYSTEM_BUSY);
            }
            locked = true;
            // 1. 校验订单是否存在
            GoodsOrder goodsOrder = getById(orderId);
            if (goodsOrder == null) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
            }

            // 2. 校验订单状态是否为待付款
            if (goodsOrder.getOrderStatus() != GoodsOrderStatus.PENDING_PAYMENT) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_STATUS_ERROR);
            }

            //3.校验是否为买家付款
            Long userId = IdHolder.getId();
            if (!goodsOrder.getBuyerId().equals(userId)) {
                throw new BusinessException(MessageConstants.NO_PERMISSION);
            }

            //4.买家付款
            boolean isSuccess = userService.lambdaUpdate()
                    .eq(User::getId, userId)
                    .ge(User::getBalance, goodsOrder.getTotalAmount())
                    .setSql("balance = balance - {0}", goodsOrder.getTotalAmount())
                    .update();
            if (!isSuccess) {
                throw new BusinessException(MessageConstants.BALANCE_NOT_ENOUGH);
            }

            //5.更新卖家余额
            isSuccess = userService.lambdaUpdate()
                    .eq(User::getId, goodsOrder.getOwnerId())
                    .setSql("balance = balance + {0}", goodsOrder.getTotalAmount())
                    .update();
            if (!isSuccess) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_PAY_ERROR);
            }

            // 6. 更新订单状态为待发货
            isSuccess = lambdaUpdate()
                    .eq(GoodsOrder::getId, orderId)
                    .eq(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_PAYMENT)
                    .set(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_SHIP)
                    .set(GoodsOrder::getPayTime, LocalDateTime.now())
                    .update();
            if (!isSuccess) {
                throw new BusinessException(MessageConstants.GOODS_ORDER_PAY_ERROR);
            }

            // 7. 发送订单付款成功消息
            //给买家发送付款成功通知
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(goodsOrder.getBuyerId())
                    .type(NotificationType.GOODS_PAY)
                    .content(MessageConstants.BUYER_GOODS_ORDER_PAY_SUCCESS)
                    .refId(orderId)
                    .build());
            //给卖家发送付款成功通知
            notificationSender.sendAsync(NotificationMessage.builder()
                    .userId(goodsOrder.getOwnerId())
                    .type(NotificationType.GOODS_PAY)
                    .content(MessageConstants.OWNER_GOODS_ORDER_PAY_SUCCESS)
                    .refId(orderId)
                    .build());


        } finally {
            // 锁释放下沉到事务提交/回滚之后，避免 unlock 早于 commit 的竞态窗口
            if (locked) {
                TransactionUtils.afterTransaction(() -> lockClient.unlock(lockKey));
            }
        }
    }

    /**
     * 发货
     *
     * @param orderId 订单ID
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_DELIVERY, descriptionEL = "'商品订单发货 orderId=' + #orderId")
    public void delivery(Long orderId) {
        // 校验订单是否存在
        GoodsOrder goodsOrder = getById(orderId);
        if (goodsOrder == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
        }

        // 校验是否为卖家发货
        Long userId = IdHolder.getId();
        if (!goodsOrder.getOwnerId().equals(userId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 校验订单状态是否为待发货
        if (goodsOrder.getOrderStatus() != GoodsOrderStatus.PENDING_SHIP) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_STATUS_ERROR);
        }

        // 更新订单状态为已发货
        boolean isSuccess = lambdaUpdate()
                .eq(GoodsOrder::getId, orderId)
                .eq(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_SHIP)
                .set(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_CONFIRM)
                .set(GoodsOrder::getShipTime, LocalDateTime.now())
                .update();
        if (!isSuccess) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_DELIVERY_ERROR);
        }

        // 发送订单发货成功消息
        notificationSender.sendAsync(NotificationMessage.builder()
                .userId(goodsOrder.getBuyerId())
                .type(NotificationType.GOODS_DELIVER)
                .content(MessageConstants.GOODS_ORDER_DELIVER_SUCCESS)
                .refId(orderId)
                .build());
    }

    /**
     * 删除订单
     *
     * @param orderId 订单ID
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_DELETE, descriptionEL = "'删除商品订单 orderId=' + #orderId")
    public void deleteOrder(Long orderId) {
        // 校验订单是否存在
        GoodsOrder goodsOrder = getById(orderId);
        if (goodsOrder == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
        }

        // 校验是否为买家或卖家删除
        Long userId = IdHolder.getId();
        // 校验是否为买家或卖家
        boolean isBuyer = goodsOrder.getBuyerId().equals(userId);
        boolean isSeller = goodsOrder.getOwnerId().equals(userId);
        if (!isBuyer && !isSeller) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 校验订单状态是否为完成
        if (goodsOrder.getOrderStatus() != GoodsOrderStatus.COMPLETED) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_STATUS_ERROR);
        }

        // 根据身份更新对应的删除标记
        if (isBuyer) {
            lambdaUpdate().set(GoodsOrder::getBuyerDeleted, 1)
                    .eq(GoodsOrder::getId, orderId)
                    .eq(GoodsOrder::getBuyerDeleted, 0)
                    .update();
        }
        if (isSeller) {
            lambdaUpdate().set(GoodsOrder::getOwnerDeleted, 1)
                    .eq(GoodsOrder::getId, orderId)
                    .eq(GoodsOrder::getOwnerDeleted, 0)
                    .update();
        }
    }

    /**
     * 状态查找我的订单
     *
     * @param orderStatus  订单状态
     * @param pageQueryDTO 分页查询参数
     * @return 订单列表VO列表
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "T(com.rambo.common.context.IdHolder).getId()",
            action = OperationActionEnum.GOODS_ORDER_VIEW_MINE, descriptionEL = "'查看我的商品订单'")
    public PageResult<GoodsOrderListVO> getMyOrderList(GoodsOrderStatus orderStatus, PageQuery pageQueryDTO) {
        Long userId = IdHolder.getId();
        log.info("用户 {} 查看我的商品订单列表", userId);

        //构造分页查询
        Page<GoodsOrder> page = new Page<>(pageQueryDTO.getPageNum(), pageQueryDTO.getPageSize());

        // 执行查询
        Page<GoodsOrder> pageList = lambdaQuery().and(w -> w.eq(GoodsOrder::getOwnerId, userId)
                        .eq(GoodsOrder::getOwnerDeleted, 0)
                        .or()
                        .eq(GoodsOrder::getBuyerId, userId)
                        .eq(GoodsOrder::getBuyerDeleted, 0))
                .eq(orderStatus != null, GoodsOrder::getOrderStatus, orderStatus)
                .orderByDesc(GoodsOrder::getCreateTime)
                .page(page);

        // 校验是否为空订单列表
        if (pageList.getTotal() == 0) {
            return new PageResult<>(pageList.getTotal(), Collections.emptyList());
        }

        // 转换为VO列表
        List<GoodsOrderListVO> itemList = pageList.getRecords().stream()
                .map(goodsOrder -> BeanUtil.copyProperties(goodsOrder, GoodsOrderListVO.class))
                .toList();

        return new PageResult<>(pageList.getTotal(), itemList);
    }

    /**
     * 根据订单ID查询订单详情
     *
     * @param orderId 订单ID
     * @return 订单详情VO
     */
    @Override
    @Log(module = OperationModuleEnum.GOODS, targetType = OperationTargetTypeEnum.ORDER,
            targetIdEL = "#orderId",
            action = OperationActionEnum.GOODS_ORDER_VIEW, descriptionEL = "'查看商品订单详情 orderId=' + #orderId")
    public GoodsOrderDetailVO getOrderDetail(Long orderId) {
        log.info("查看商品订单详情 orderId={}", orderId);
        // 校验订单是否存在
        GoodsOrder goodsOrder = getById(orderId);
        if (goodsOrder == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_NOT_FOUND);
        }

        //校验商品快照项是否存在
        OrderItem orderItem = orderItemService.lambdaQuery()
                .eq(OrderItem::getOrderId, orderId)
                .one();
        if (orderItem == null) {
            throw new BusinessException(MessageConstants.GOODS_ORDER_ITEM_NOT_FOUND);
        }

        // 校验是否为买家或卖家查询
        Long userId = IdHolder.getId();
        if (!goodsOrder.getBuyerId().equals(userId) && !goodsOrder.getOwnerId().equals(userId)) {
            throw new BusinessException(MessageConstants.NO_PERMISSION);
        }

        // 转换为VO
        GoodsOrderDetailVO detail = BeanUtil.copyProperties(goodsOrder, GoodsOrderDetailVO.class);

        // 合并商品快照项信息为VO
        BeanUtil.copyProperties(orderItem, detail);
        return detail;
    }

    /**
     * 判断用户是否是订单参与人
     * @param orderId 订单ID
     * @param userId 用户ID
     * @return 是否是订单参与人
     */
    @Override
    public boolean isParticipant(Long orderId, Long userId) {
        return lambdaQuery()
                .eq(GoodsOrder::getId, orderId)
                .and(w -> w
                        .eq(GoodsOrder::getBuyerId, userId)
                        .or()
                        .eq(GoodsOrder::getOwnerId, userId))
                .exists();
    }
}
