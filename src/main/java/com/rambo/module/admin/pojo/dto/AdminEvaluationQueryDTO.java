package com.rambo.module.admin.pojo.dto;

import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminEvaluationQueryDTO extends PageQuery implements Serializable {
    @Schema(description = "评价人用户ID")
    private Long fromUid;

    @Schema(description = "被评价人用户ID")
    private Long toUid;

    @Schema(description = "最低评分")
    private Integer minScore;

    @Schema(description = "最高评分")
    private Integer maxScore;

    @Schema(description = "起始评价时间")
    private LocalDateTime startCreateTime;

    @Schema(description = "结束评价时间")
    private LocalDateTime endCreateTime;
}
