package com.rambo.module.admin.pojo.dto;

import com.rambo.common.enumType.CategoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class AdminGoodsCategoryDTO implements Serializable {
    @Schema(description = "商品分类名称")
    @NotBlank(message = "商品分类名称不能为空")
    private String name;

    @Schema(description = "商品分类描述")
    @NotBlank(message = "商品分类描述不能为空")
    private String description;

    @Schema(description = "商品分类状态：0-正常，1-禁用")
    @NotNull(message = "商品分类状态不能为空")
    private CategoryStatus status;
}
