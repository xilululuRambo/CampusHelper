package com.rambo.module.task.pojo.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("t_task_rank_monthly")
public class RankTaskMonthly implements Serializable {
    @Schema(description = "主键")
    private Long id;

    @Schema(description = "用户ID")
    private Long userId;

    @Schema(description = "完成任务数量")
    private Integer finishCount;

    @Schema(description = "当月排名")
    private Integer rankNum;

    @Schema(description = "月份")
    private String month;

    @Schema(description = "创建时间")
    private LocalDateTime createdAt;
}
