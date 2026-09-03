package com.rambo.common.result;

import com.rambo.common.constants.NumConstants;
import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

@Data
@Schema(description = "分页查询DTO")
public class PageQuery {

    @Schema(description = "页码")
    @Min(value = 1, message = "页码必须大于0")
    private Integer pageNum = NumConstants.DEFAULT_PAGE_NUM;

    @Schema(description = "每页条数")
    @Min(value = NumConstants.PAGE_SIZE_MIN, message = "每页条数不能小于1")
    @Max(value = NumConstants.PAGE_SIZE_MAX, message = "每页条数不能超过" + NumConstants.PAGE_SIZE_MAX)
    private Integer pageSize = NumConstants.DEFAULT_PAGE_SIZE;
}