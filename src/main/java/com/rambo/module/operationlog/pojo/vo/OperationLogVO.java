package com.rambo.module.operationlog.pojo.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class OperationLogVO implements Serializable {

    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "操作时间戳")
    private LocalDateTime createTime;

    @Schema(description = "操作人ID（匿名访问为0）")
    private Long operatorId;

    @Schema(description = "操作人角色code")
    private Integer operatorRoleCode;

    @Schema(description = "操作人角色名称")
    private String operatorRoleName;

    @Schema(description = "操作模块code")
    private Integer moduleCode;

    @Schema(description = "操作模块名称")
    private String moduleName;

    @Schema(description = "操作对象类型code")
    private Integer targetTypeCode;

    @Schema(description = "操作对象类型名称")
    private String targetTypeName;

    @Schema(description = "操作对象ID")
    private Long targetId;

    @Schema(description = "操作类型code")
    private Integer actionCode;

    @Schema(description = "操作类型名称")
    private String actionName;

    @Schema(description = "操作描述")
    private String description;

    @Schema(description = "操作结果 0-成功 1-失败")
    private Integer result;

    @Schema(description = "失败原因摘要")
    private String errorMsg;

    @Schema(description = "设备ID（仅USER）")
    private Long deviceId;

    @Schema(description = "请求URI")
    private String requestUri;

    @Schema(description = "请求方法")
    private String requestMethod;

    @Schema(description = "接口耗时(ms)")
    private Long durationMs;

    @Schema(description = "链路追踪ID")
    private String traceId;
}
