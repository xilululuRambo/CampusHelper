package com.rambo.module.goods.server.controller;

import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.common.result.PageQuery;
import com.rambo.common.result.PageResult;
import com.rambo.common.result.Result;
import com.rambo.module.goods.pojo.vo.GoodsOrderDetailVO;
import com.rambo.module.goods.pojo.vo.GoodsOrderListVO;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsWorkflowService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/goods/order")
@Tag(name = "商品订单接口")
@Slf4j
@Validated
public class GoodsOrderController {

    @Resource
    private GoodsOrderService goodsOrderService;

    @Resource
    private GoodsWorkflowService goodsWorkflowService;

    /**
     * 付款
     *
     * @param orderId 订单ID
     * @return 无返回值
     */
    @PostMapping("pay/{orderId}")
    @Operation(summary = "付款")
    public Result<Void> pay(@PathVariable Long orderId) {
        log.info("付款，orderId={}", orderId);
        goodsOrderService.pay(orderId);
        return Result.success();
    }

    /**
     * 取消订单
     *
     * @param orderId 订单ID
     * @return 无返回值
     */
    @PostMapping("cancel/{orderId}")
    @Operation(summary = "取消订单")
    public Result<Void> cancelOrder(@PathVariable Long orderId) {
        log.info("取消订单，orderId={}", orderId);
        goodsWorkflowService.cancelOrder(orderId);
        return Result.success();
    }

    /**
     * 发货
     *
     * @param orderId 订单ID
     * @return 无返回值
     */
    @PostMapping("delivery/{orderId}")
    @Operation(summary = "发货")
    public Result<Void> delivery(@PathVariable Long orderId) {
        log.info("发货，orderId={}", orderId);
        goodsOrderService.delivery(orderId);
        return Result.success();
    }

    /**
     * 确认收货订单完成
     *
     * @param orderId 订单ID
     * @return 无返回值
     */
    @PostMapping("confirm/{orderId}")
    @Operation(summary = "确认收货订单完成")
    public Result<Void> confirmOrder(@PathVariable Long orderId) {
        log.info("确认收货订单完成，orderId={}", orderId);
        goodsWorkflowService.confirmOrder(orderId);
        return Result.success();
    }

    /**
     * 删除订单
     *
     * @param orderId 订单ID
     * @return 无返回值
     */
    @DeleteMapping("/{orderId}")
    @Operation(summary = "删除订单")
    public Result<Void> deleteOrder(@PathVariable Long orderId) {
        log.info("删除订单，orderId={}", orderId);
        goodsOrderService.deleteOrder(orderId);
        return Result.success();
    }

    /**
     * 状态查找我的订单
     *
     * @param orderStatus  订单状态
     * @param pageQueryDTO 分页查询参数
     * @return 订单列表VO列表
     */
    @GetMapping("/my")
    @Operation(summary = "状态查找我的订单")
    public Result<PageResult<GoodsOrderListVO>> getMyOrderList(@RequestParam(required = false) GoodsOrderStatus orderStatus,
                                                               PageQuery pageQueryDTO) {
        log.info("状态查找我的订单");
        PageResult<GoodsOrderListVO> pageResult = goodsOrderService.getMyOrderList(orderStatus, pageQueryDTO);
        return Result.success(pageResult);
    }

    /**
     * 根据订单ID查询订单详情
     *
     * @param orderId 订单ID
     * @return 订单详情VO
     */
    @GetMapping("/{orderId}")
    @Operation(summary = "根据订单ID查询订单详情")
    public Result<GoodsOrderDetailVO> getOrderDetail(@PathVariable Long orderId) {
        log.info("根据订单ID查询订单详情，orderId={}", orderId);
        GoodsOrderDetailVO orderDetail = goodsOrderService.getOrderDetail(orderId);
        return Result.success(orderDetail);
    }
}
