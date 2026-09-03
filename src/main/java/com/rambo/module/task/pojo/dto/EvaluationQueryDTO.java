package com.rambo.module.task.pojo.dto;

import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = true)
@Data
@Schema(name = "EvaluationQueryDTO", description = "评价查询DTO")
public class EvaluationQueryDTO extends PageQuery {
}