package com.rambo.module.goods.pojo.vo;

import com.rambo.module.goods.enums.GoodsOrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GoodsOrderListVO implements Serializable {

    @Schema(description = "商品订单ID")
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "发布者用户ID")
    private Long ownerId;

    @Schema(description = "购买者用户ID")
    private Long buyerId;

    @Schema(description = "订单总金额（实际支付金额，单位分）")
    private Long totalAmount;

    @Schema(description = "订单状态")
    private GoodsOrderStatus orderStatus;

    @Schema(description = "订单付款时间")
    private LocalDateTime payTime;

    @Schema(description = "订单发货时间")
    private LocalDateTime shipTime;

    @Schema(description = "确认收货时间")
    private LocalDateTime confirmTime;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}