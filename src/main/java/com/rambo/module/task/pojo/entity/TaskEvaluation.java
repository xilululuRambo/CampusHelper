package com.rambo.module.task.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_task_evaluation")
public class TaskEvaluation implements Serializable {

    @Schema(description = "任务评价ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "任务订单ID")
    private Long orderId;

    @Schema(description = "任务评价人用户ID")
    private Long fromUid;

    @Schema(description = "任务被评价人用户ID")
    private Long toUid;

    @Schema(description = "任务评价分数 （0-5）")
    private Integer score;

    @Schema(description = "任务评价内容")
    private String content;

    @Schema(description = "任务评价时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}