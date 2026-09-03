package com.rambo.module.task.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.module.task.enums.TaskStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_task")
public class Task implements Serializable {

    @Schema(description = "任务ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "发布者用户ID")
    private Long publisherId;

    @Schema(description = "接单者用户ID")
    private Long applicantId;

    @Schema(description = "任务标题")
    private String title;

    @Schema(description = "任务描述")
    private String description;

    @Schema(description = "奖励（积分）")
    private Integer reward;

    @Schema(description = "任务分类ID")
    private Long categoryId;

    @Schema(description = "任务地址ID")
    private Long addressId;

    @Schema(description = "截止时间")
    private LocalDateTime deadline;

    @Schema(description = "任务状态：0-待接单 1-进行中 2-待确认 3-已完成 4-已取消")
    private TaskStatus status;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @Version
    @Schema(description = "乐观锁版本号")
    private Integer version;
}