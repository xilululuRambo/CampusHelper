package com.rambo.module.admin.pojo.dto;

import com.rambo.module.task.enums.TaskStatus;
import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
public class AdminTaskQueryDTO extends PageQuery implements Serializable {
    @Schema(description = "发布者用户ID")
    private Long publisherId;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "起始奖励（积分）")
    private Integer startReward;

    @Schema(description = "结束奖励（积分）")
    private Integer endReward;

    @Schema(description = "任务分类ID")
    private Long categoryId;

    @Schema(description = "起始截止时间")
    private LocalDateTime startDeadline;

    @Schema(description = "结束截止时间")
    private LocalDateTime endDeadline;

    @Schema(description = "任务状态：0-待接单 1-进行中 2-待确认 3-已完成 4-已取消")
    private TaskStatus status;

    @Schema(description = "起始创建时间")
    private LocalDateTime startCreateTime;

    @Schema(description = "结束创建时间")
    private LocalDateTime endCreateTime;
}
