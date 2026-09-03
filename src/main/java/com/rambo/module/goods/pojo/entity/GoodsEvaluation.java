package com.rambo.module.goods.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_goods_evaluation")
public class GoodsEvaluation implements Serializable {

    @Schema(description = "商品评价ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "商品订单ID")
    private Long orderId;

    @Schema(description = "商品评价人用户ID")
    private Long fromUid;

    @Schema(description = "商品被评价人用户ID")
    private Long toUid;

    @Schema(description = "商品评价分数 （0-5）")
    private Integer score;

    @Schema(description = "商品评价内容")
    private String content;

    @Schema(description = "商品评价时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}