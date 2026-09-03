package com.rambo.module.goods.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
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
@TableName("t_goods_order")
public class GoodsOrder implements Serializable {

    @Schema(description = "商品订单ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "商品id")
    private Long goodsId;

    @Schema(description = "发布者用户ID")
    private Long ownerId;

    @Schema(description = "购买者用户ID")
    private Long buyerId;

    @Schema(description = "订单总金额（实际支付金额，单位分）")
    private Long totalAmount;

    // 订单状态：0-待付款，1-待发货，2-待收货，3-已完成 ，4-已取消
    @Schema(description = "订单状态")
    private GoodsOrderStatus orderStatus;

    @Schema(description = "订单付款时间")
    private LocalDateTime payTime;

    @Schema(description = "订单发货时间")
    private LocalDateTime shipTime;

    @Schema(description = "确认收货时间")
    private LocalDateTime confirmTime;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @Version
    @Schema(description = "乐观锁版本号")
    private Integer version;

    @Schema(description = "购买者逻辑删除 0-未删除 1-已删除")
    private Integer buyerDeleted;

    @Schema(description = "发布者逻辑删除 0-未删除 1-已删除")
    private Integer ownerDeleted;
}