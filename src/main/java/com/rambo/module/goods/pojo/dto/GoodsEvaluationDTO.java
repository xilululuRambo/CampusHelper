package com.rambo.module.goods.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(description = "新增评价请求DTO")
public class GoodsEvaluationDTO {
    @NotNull(message = "订单ID不能为空")
    @Schema(description = "订单ID")
    private Long orderId;

    @NotNull(message = "被评价用户ID不能为空")
    @Schema(description = "被评价用户ID")
    private Long toUserId;

    @NotNull(message = "评分不能为空")
    @Min(1)
    @Max(5)
    @Schema(description = "评分（1-5）")
    private Integer score;

    @NotNull(message = "评价内容不能为空")
    @Schema(description = "评价内容")
    private String content;
}