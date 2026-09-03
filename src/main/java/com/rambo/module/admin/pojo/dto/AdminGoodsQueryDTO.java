package com.rambo.module.admin.pojo.dto;

import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminGoodsQueryDTO extends PageQuery implements Serializable {
    @Schema(description = "搜索关键词（标题模糊）")
    private String keyword;

    @Schema(description = "商品状态：0-在售 1-交易中 2-下架 3-已售出")
    private GoodsStatus status;

    @Schema(description = "商品分类ID")
    private Long categoryId;

    @Schema(description = "发布者用户ID")
    private Long ownerId;

    @Schema(description = "起始价格（分）")
    private Long startPrice;

    @Schema(description = "结束价格（分）")
    private Long endPrice;

    @Schema(description = "起始创建时间")
    private LocalDateTime startCreateTime;

    @Schema(description = "结束创建时间")
    private LocalDateTime endCreateTime;
}
