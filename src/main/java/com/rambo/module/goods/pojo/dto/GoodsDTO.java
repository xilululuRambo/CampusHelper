package com.rambo.module.goods.pojo.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GoodsDTO implements Serializable {
    @Schema(description = "商品标题")
    @NotBlank(message = "商品标题不能为空")
    private String title;

    @Schema(description = "商品描述")
    @NotBlank(message = "商品描述不能为空")
    private String description;

    @Schema(description = "价格（分）")
    @NotNull(message = "价格不能为空")
    private Long price;

    @Schema(description = "商品分类")
    @NotNull(message = "商品分类不能为空")
    private Long categoryId;

}