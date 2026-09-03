package com.rambo.module.task.pojo.dto;

import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@Schema(description = "我的订单查询DTO")
public class TaskOrderQueryDTO extends PageQuery {
}