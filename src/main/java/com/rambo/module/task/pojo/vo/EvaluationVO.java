package com.rambo.module.task.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(name = "EvaluationVO", description = "评价展示VO")
public class EvaluationVO {

    @Schema(description = "评价ID")
    private Long id;

    @Schema(description = "订单ID")
    private Long orderId;

    @Schema(description = "评价人ID")
    private Long fromUid;

    @Schema(description = "被评价人ID")
    private Long toUid;

    @Schema(description = "评分")
    private Integer score;

    @Schema(description = "评价内容")
    private String content;

    @Schema(description = "评价时间")
    private LocalDateTime createTime;
}