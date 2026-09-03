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
@TableName("t_task_order")
public class TaskOrder implements Serializable {

    @Schema(description = "成交订单ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "发布人用户ID")
    private Long publisherId;

    @Schema(description = "接收人用户ID")
    private Long receiverId;

    @Schema(description = "订单创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}