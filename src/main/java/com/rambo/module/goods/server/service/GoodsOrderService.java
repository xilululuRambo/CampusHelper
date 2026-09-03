package com.rambo.module.goods.server.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.module.goods.pojo.dto.GoodsOrderDTO;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.pojo.vo.GoodsOrderDetailVO;
import com.rambo.module.goods.pojo.vo.GoodsOrderListVO;

public interface GoodsOrderService extends IService<GoodsOrder> {
    /**
     * 自动创建订单
     */
    Long createOrder(GoodsOrderDTO goodsOrderDTO);

    /**
     * 付款
     * @param orderId 订单ID
     */
    void pay(Long orderId);
    /**
     * 发货
     * @param orderId 订单ID
     */
    void delivery(Long orderId);
    /**
     * 删除订单
     * @param orderId 订单ID
     */
    void deleteOrder(Long orderId);

    /**
     * 状态查找我的订单
     * @param orderStatus 订单状态
     * @param pageQueryDTO 分页查询参数
     * @return 订单列表VO列表
     */
    PageResult<GoodsOrderListVO> getMyOrderList(GoodsOrderStatus orderStatus, PageQuery pageQueryDTO);

    /**
     * 根据订单ID查询订单详情
     * @param orderId 订单ID
     * @return 订单详情VO
     */
    GoodsOrderDetailVO getOrderDetail(Long orderId);

    /**
     * 判断用户是否是订单参与人
     * @param orderId 订单ID
     * @param userId 用户ID
     * @return 是否是订单参与人
     */
    boolean isParticipant(Long orderId, Long userId);
}
