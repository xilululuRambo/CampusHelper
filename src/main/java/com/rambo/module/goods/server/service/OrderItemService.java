package com.rambo.module.goods.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.goods.pojo.dto.OrderItemDTO;
import com.rambo.module.goods.pojo.entity.OrderItem;

public interface OrderItemService extends IService<OrderItem> {
    /**
     * 创建订单商品快照
     * @param orderItemDTO 订单商品DTO
     */
    void createOrderItem(OrderItemDTO orderItemDTO);
}
