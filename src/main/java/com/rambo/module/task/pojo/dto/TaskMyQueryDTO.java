package com.rambo.module.task.pojo.dto;

import com.rambo.module.task.enums.TaskStatus;
import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class TaskMyQueryDTO extends PageQuery implements Serializable {

    @Schema(description = "任务分类ID")
    private Long categoryId;

    @Schema(description = "任务状态")
    private TaskStatus status;

}
