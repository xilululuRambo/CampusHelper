package com.rambo.module.task.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskApplicationVO implements Serializable {
    @Schema(description = "任务申请ID")
    private Long id;

    @Schema(description = "任务ID")
    private Long taskId;

    @Schema(description = "申请人用户ID")
    private Long applicantId;

    @Schema(description = "申请状态：0-待处理 1-已接受 2-已拒绝 3-已完成 4-已取消")
    private Integer status;

    @Schema(description = "申请原因")
    private String reason;

    @Schema(description = "创建时间")
    private LocalDateTime createTime;
}