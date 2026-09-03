package com.rambo.module.goods.server.job;

import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.cache.LockClient;
import com.rambo.module.chat.server.service.ChatSessionService;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsService;
import com.xxl.job.core.context.XxlJobHelper;
import com.xxl.job.core.handler.annotation.XxlJob;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * XXL-JOB：关闭超时未付款订单
 *
 * <p>批量取消过期订单 → 恢复交易中的商品为在售 → 关闭关联会话（历史订单无会话时跳过）。</p>
 *
 * <p>并发安全设计：</p>
 * <ul>
 *   <li>分布式锁（Redis）：防止 XXL-JOB 多执行器/手动重试并发触发重复处理；</li>
 *   <li>逐单 CAS（UPDATE 带 PENDING_PAYMENT 状态条件）：SELECT 与 UPDATE 之间买家完成支付的订单
 *       影响行数为 0，自动跳过取消，避免误取消已付款订单（无退款将钱货两空）；</li>
 *   <li>幂等：已取消/已支付订单不再满足查询条件，重试不会二次处理。</li>
 * </ul>
 */
@Slf4j
@Component
public class GoodsOrderTimeoutJob {

    @Resource
    private GoodsOrderService goodsOrderService;
    @Resource
    private GoodsService goodsService;
    @Resource
    private ChatSessionService chatSessionService;
    @Resource
    private LockClient lockClient;

    @XxlJob("goodsOrderTimeoutJob")
    public void execute() {
        // 分布式锁：抢不到说明其他执行器正在处理，本次直接跳过（不空转、不重复处理）
        boolean locked = lockClient.tryLock(PrefixConstants.GOODS_ORDER_TIMEOUT_LOCK, 0, TimeUnit.SECONDS);
        if (!locked) {
            XxlJobHelper.log("超时关单任务正在其他执行器运行，本次跳过");
            return;
        }
        try {
            doExecute();
        } finally {
            lockClient.unlock(PrefixConstants.GOODS_ORDER_TIMEOUT_LOCK);
        }
    }

    private void doExecute() {
        // 查询待付款订单
        List<GoodsOrder> list = goodsOrderService.lambdaQuery()
                .eq(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_PAYMENT)
                .lt(GoodsOrder::getCreateTime, LocalDateTime.now().minusMinutes(NumConstants.PENDING_PAYMENT_EXPIRE_MINUTES))
                .list();

        // 没有过期订单，直接返回
        if (list == null || list.isEmpty()) {
            return;
        }

        // 逐单 CAS 取消：UPDATE 必须带 PENDING_PAYMENT 状态条件。
        // 若 SELECT 与 UPDATE 之间买家已完成支付（状态变为 PENDING_SHIP），该单影响行数为 0，跳过取消；
        // 只有真正从待付款置为已取消的订单才进入后续商品恢复/会话关闭。
        List<Long> cancelledIds = new ArrayList<>();
        for (GoodsOrder order : list) {
            boolean cancelled = goodsOrderService.lambdaUpdate()
                    .eq(GoodsOrder::getId, order.getId())
                    .eq(GoodsOrder::getOrderStatus, GoodsOrderStatus.PENDING_PAYMENT)
                    .set(GoodsOrder::getOrderStatus, GoodsOrderStatus.CANCELLED)
                    .update();
            if (cancelled) {
                cancelledIds.add(order.getId());
            }
        }

        // 没有实际取消的订单（可能全部已完成支付），直接返回
        if (cancelledIds.isEmpty()) {
            return;
        }

        // 仅恢复被真正取消订单的商品为在售（仍带 TRADING 状态条件，防止与并发购买冲突）
        List<Long> goodsIds = list.stream()
                .filter(order -> cancelledIds.contains(order.getId()))
                .map(GoodsOrder::getGoodsId).distinct().toList();
        goodsService.lambdaUpdate()
                .in(Goods::getId, goodsIds)
                .eq(Goods::getStatus, GoodsStatus.TRADING)
                .set(Goods::getStatus, GoodsStatus.NORMAL)
                .update();
        // 商品状态已变更（Job 直改绕过 @CacheEvict 业务方法）：逐个失效详情缓存，防止残留旧状态
        for (Long goodsId : goodsIds) {
            goodsService.evictGoodsDetail(goodsId);
        }

        // 关闭对应会话（历史订单可能无会话记录，跳过不中断批量任务）
        for (Long orderId : cancelledIds) {
            try {
                chatSessionService.closeSession("goods_" + orderId);
            } catch (BusinessException e) {
                log.warn("关闭订单会话失败，订单ID：{}，原因：{}", orderId, e.getMessage());
            }
        }

        XxlJobHelper.handleSuccess("处理完成，共关闭 " + cancelledIds.size() + " 个超时订单");
    }
}
