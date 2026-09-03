package com.rambo.module.admin.pojo.vo;

import com.rambo.module.goods.enums.GoodsStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class AdminGoodsListItemVO implements Serializable {

    @Schema(description = "商品ID")
    private Long id;

    @Schema(description = "发布者用户ID")
    private Long ownerId;

    @Schema(description = "商品标题")
    private String title;

    @Schema(description = "商品分类ID")
    private Long categoryId;

    @Schema(description = "价格（分）")
    private Long price;

    @Schema(description = "商品图片URL，多张用逗号分隔")
    private String images;

    @Schema(description = "商品状态：0-在售 1-交易中 2-下架 3-已售出")
    private GoodsStatus status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
