package com.rambo.module.task.pojo.dto;

import com.rambo.module.task.enums.TaskApplyStatus;
import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
@NoArgsConstructor
@Data
public class TaskApplicationQueryDTO extends PageQuery implements Serializable {
    @Schema(description = "申请状态")
    private TaskApplyStatus applyStatus;
}
