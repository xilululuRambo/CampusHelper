package com.rambo.module.goods.server.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.goods.pojo.dto.OrderItemDTO;
import com.rambo.module.goods.pojo.entity.OrderItem;
import com.rambo.module.goods.server.mapper.OrderItemMapper;
import com.rambo.module.goods.server.service.OrderItemService;
import org.springframework.stereotype.Service;

@Service
public class OrderItemServiceImpl extends ServiceImpl<OrderItemMapper, OrderItem> implements OrderItemService {
    /**
     * 创建订单商品快照
     * @param orderItemDTO 订单商品DTO
     */
    @Override
    public void createOrderItem(OrderItemDTO orderItemDTO) {
        // 校验参数
        if (orderItemDTO.getGoodsId() == null) {
            throw new BusinessException(MessageConstants.GOODS_ID_EMPTY);
        }
        if (orderItemDTO.getOrderId() == null) {
            throw new BusinessException(MessageConstants.ORDER_ID_EMPTY);
        }
        if (orderItemDTO.getGoodsTitle() == null) {
            throw new BusinessException(MessageConstants.GOODS_TITLE_EMPTY);
        }
        if (orderItemDTO.getDescription() == null) {
            throw new BusinessException(MessageConstants.DESCRIPTION_EMPTY);
        }
        if (orderItemDTO.getImages() == null) {
            throw new BusinessException(MessageConstants.IMAGES_EMPTY);
        }
        if (orderItemDTO.getPrice() <= 0) {
            throw new BusinessException(MessageConstants.PRICE_EMPTY);
        }

        // 创建订单商品快照
        OrderItem orderItem = new OrderItem();
        orderItem.setOrderId(orderItemDTO.getOrderId());
        orderItem.setGoodsId(orderItemDTO.getGoodsId());
        orderItem.setGoodsTitle(orderItemDTO.getGoodsTitle());
        orderItem.setDescription(orderItemDTO.getDescription());
        orderItem.setImages(orderItemDTO.getImages());
        orderItem.setPrice(orderItemDTO.getPrice());
        saveOrUpdate(orderItem);
    }
}
