package com.rambo.module.goods.pojo.dto;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
@AllArgsConstructor
@NoArgsConstructor
@Data
public class OrderItemDTO implements Serializable {
    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "商品ID")
    private Long goodsId;

    @Schema(description = "商品标题")
    private String goodsTitle;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "商品图片")
    private String images;

    @Schema(description = "商品单价（分）")
    private Long price;
}
