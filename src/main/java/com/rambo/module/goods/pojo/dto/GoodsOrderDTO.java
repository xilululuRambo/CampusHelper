package com.rambo.module.goods.pojo.dto;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_goods_order")
public class GoodsOrderDTO implements Serializable {
    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "发布者用户ID")
    private Long ownerId;

    @Schema(description = "购买者用户ID")
    private Long buyerId;

    @Schema(description = "订单总金额（实际支付金额，单位分）")
    private Long totalAmount;
}