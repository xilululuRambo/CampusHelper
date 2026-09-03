package com.rambo.module.goods.server.service;

public interface GoodsWorkflowService {
    /**
     * 确认收货订单完成（编排层）
     * @param orderId 订单ID
     */
    void confirmOrder(Long orderId);

    /**
     * 取消订单（编排层）
     * @param orderId 订单ID
     */
    void cancelOrder(Long orderId);

    /**
     * 管理员强制取消订单（编排层：锁 + 校验 + 退款 + 取消 + 通知双方）
     * 已付款订单（待发货/待收货）执行退款：扣回卖家余额 + 退回买家余额；待付款订单直接取消
     * @param orderId 订单ID
     */
    void cancelOrderByAdmin(Long orderId);
}