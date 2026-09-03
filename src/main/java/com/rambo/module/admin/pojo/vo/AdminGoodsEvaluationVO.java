package com.rambo.module.admin.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@Schema(description = "管理员商品评价列表项")
public class AdminGoodsEvaluationVO implements Serializable {

    @Schema(description = "评价ID")
    private Long id;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "评价人用户ID")
    private Long fromUid;

    @Schema(description = "被评价人用户ID")
    private Long toUid;

    @Schema(description = "评分（0-5）")
    private Integer score;

    @Schema(description = "评价内容")
    private String content;

    @Schema(description = "评价时间")
    private LocalDateTime createTime;
}
