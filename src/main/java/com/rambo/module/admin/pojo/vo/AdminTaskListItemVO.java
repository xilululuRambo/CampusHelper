package com.rambo.module.admin.pojo.vo;

import com.rambo.module.task.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
public class AdminTaskListItemVO {

    @Schema(description = "任务ID")
    private Long id;

    @Schema(description = "发布者用户ID")
    private Long publisherId;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "奖励（积分）")
    private Integer reward;

    @Schema(description = "任务分类ID")
    private Long categoryId;

    @Schema(description = "截止时间")
    private LocalDateTime deadline;

    @Schema(description = "任务状态：0-待接单 1-进行中 2-待确认 3-已完成 4-已取消")
    private TaskStatus status;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}
