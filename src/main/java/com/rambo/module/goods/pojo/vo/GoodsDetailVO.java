package com.rambo.module.goods.pojo.vo;

import com.rambo.common.annotation.OssUrl;
import com.rambo.module.goods.enums.GoodsStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class GoodsDetailVO implements Serializable {

    @Schema(description = "商品ID")
    private Long id;

    @Schema(description = "发布者用户ID")
    private Long ownerId;

    @Schema(description = "商品标题")
    private String title;

    @Schema(description = "商品描述")
    private String description;

    @Schema(description = "价格（分单位）")
    private Long price;

    @Schema(description = "商品图片URL，多张用逗号分隔")
    @OssUrl
    private List<String> images;

    @Schema(description = "商品状态：0-在售，1-交易中，2-下架，3-已售出")
    private GoodsStatus status;
}