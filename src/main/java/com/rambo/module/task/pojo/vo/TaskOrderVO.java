package com.rambo.module.task.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Schema(description = "任务订单VO")
public class TaskOrderVO {

    @Schema(description = "订单ID")
    private Long id;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "发布者ID")
    private Long publisherId;

    @Schema(description = "接单者ID")
    private Long receiverId;

    @Schema(description = "订单创建时间")
    private LocalDateTime createTime;

    @Schema(description = "当前用户是否已评价")
    private Boolean evaluated;
}