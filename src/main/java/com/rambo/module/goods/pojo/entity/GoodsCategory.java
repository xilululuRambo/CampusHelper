package com.rambo.module.goods.pojo.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rambo.common.enumType.CategoryStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_goods_category")
public class GoodsCategory implements Serializable {

    @Schema(description = "商品分类ID")
    private Long id;

    @Schema(description = "商品分类名称")
    private String name;

    @Schema(description = "商品分类描述")
    private String description;

    @Schema(description = "商品分类状态：0-正常，1-禁用")
    private CategoryStatus status;

    @Schema(description = "商品分类创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "商品分类更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;
}