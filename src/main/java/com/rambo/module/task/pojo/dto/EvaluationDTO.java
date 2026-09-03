package com.rambo.module.task.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
@Schema(name = "EvaluationDTO", description = "评价提交DTO")
public class EvaluationDTO {

    @NotNull(message = "订单ID不能为空")
    @Schema(description = "订单ID")
    private Long orderId;

    @Min(value = 1, message = "评分最少1分")
    @Max(value = 5, message = "评分最多5分")
    @NotNull(message = "评分不能为空")
    @Schema(description = "评分 1-5")
    private Integer score;

    @NotBlank(message = "评价内容不能为空")
    @Schema(description = "评价内容")
    private String content;
}