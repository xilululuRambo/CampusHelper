package com.rambo.module.task.pojo.dto;

import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskQueryDTO extends PageQuery implements Serializable {

    @Schema(description = "任务分类ID")
    private Long categoryId;

    @Schema(description = "任务描述关键词")
    private String keyword;

    @Schema(description = "奖励（积分）开始")
    private Integer startReward;

    @Schema(description = "奖励（积分）结束")
    private Integer endReward;

    @Schema(description = "发布时间开始")
    private LocalDateTime startTime;

    @Schema(description = "发布时间结束")
    private LocalDateTime endTime;
}
