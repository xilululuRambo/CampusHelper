package com.rambo.module.goods.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GoodsCategoryVO implements Serializable {

    @Schema(description = "商品分类ID")
    private Long id;

    @Schema(description = "商品分类名称")
    private String name;
}
