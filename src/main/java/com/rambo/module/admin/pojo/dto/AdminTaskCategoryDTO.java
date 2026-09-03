package com.rambo.module.admin.pojo.dto;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.common.enumType.CategoryStatus;
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
public class AdminTaskCategoryDTO implements Serializable {
    @Schema(description = "任务分类名称")
    @NotBlank(message = "任务分类名称不能为空")
    private String name;

    @Schema(description = "任务分类描述")
    @NotBlank(message = "任务分类描述不能为空")
    private String description;

    @Schema(description = "任务分类状态：0-正常，1-禁用")
    @NotNull(message = "任务分类状态不能为空")
    private CategoryStatus status;
}