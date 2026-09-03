package com.rambo.module.task.pojo.entity;

import com.baomidou.mybatisplus.annotation.*;
import com.rambo.module.task.enums.TaskApplyStatus;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_task_application")
public class TaskApplication implements Serializable {

    @Schema(description = "任务申请ID")
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "申请人用户ID")
    private Long applicantId;

    @Schema(description = "申请原因")
    private String reason;

    @Schema(description = "申请状态：0-待处理，1-已接受，2-已拒绝，3-已完成，4-已取消")
    private TaskApplyStatus status;

    @Schema(description = "创建时间")
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;

    @Schema(description = "更新时间")
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updateTime;

    @Schema(description = "完成证据")
    private String completeEvidence;

    @Schema(description = "完成时间")
    private LocalDateTime completeTime;

    @Version
    @Schema(description = "乐观锁版本号")
    private Integer version;
}