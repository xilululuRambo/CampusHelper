package com.rambo.module.operationlog.pojo.dto;

import com.rambo.common.result.PageQuery;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serializable;
import java.time.LocalDateTime;

@EqualsAndHashCode(callSuper = true)
@Data
public class OperationLogQueryDTO extends PageQuery implements Serializable {

    @Schema(description = "操作人ID（匿名访问为0）")
    private Long operatorId;

    @Schema(description = "操作人角色code：0-用户 1-管理员 2-超级管理员 3-匿名")
    private Integer operatorRole;

    @Schema(description = "操作模块code")
    private Integer module;

    @Schema(description = "操作对象类型code")
    private Integer targetType;

    @Schema(description = "操作类型code")
    private Integer action;

    @Schema(description = "操作结果：0-成功 1-失败")
    private Integer result;

    @Schema(description = "描述关键字（模糊匹配）")
    private String keyword;

    @Schema(description = "起始操作时间")
    private LocalDateTime startCreateTime;

    @Schema(description = "结束操作时间")
    private LocalDateTime endCreateTime;
}
