package com.rambo.module.goods.pojo.dto;

import com.rambo.common.enumType.SortDirectionEnum;
import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.math.BigDecimal;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class GoodsQueryDTO extends PageQuery implements Serializable {

    @Schema(description = "搜索关键词")
    private String keyword;

    @Schema(description = "最小价格（分）")
    private Long minPrice;

    @Schema(description = "最大价格（分）")
    private Long maxPrice;

    @Schema(description = "排序方向")
    private SortDirectionEnum sortDirection;

}