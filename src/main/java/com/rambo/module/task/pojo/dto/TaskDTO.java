package com.rambo.module.task.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskDTO implements Serializable {

    @Schema(description = "任务标题")
    @NotBlank(message = "任务标题不能为空")
    private String title;

    @Schema(description = "任务描述")
    @NotBlank(message = "任务描述不能为空")
    private String description;

    @Schema(description = "奖励（积分）")
    @NotNull(message = "奖励（积分）不能为空")
    private Integer reward;

    @Schema(description = "任务分类")
    @NotNull(message = "任务分类不能为空")
    private Long categoryId;

    @Schema(description = "任务地址Id")
    @NotNull(message = "任务地址Id不能为空")
    private Long addressId;

    @Schema(description = "截止时间")
    @NotNull(message = "截止时间不能为空")
    private LocalDateTime deadline;
}