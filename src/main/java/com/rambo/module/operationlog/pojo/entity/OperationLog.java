package com.rambo.module.operationlog.pojo.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.operationlog.enums.OperatorRoleEnum;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 操作日志实体（横切关注点数据载体）
 */
@Data
@Builder
@TableName("t_operation_log")
public class OperationLog implements Serializable {

    @TableId(type = IdType.ASSIGN_ID)
    @Schema(description = "主键ID")
    private Long id;

    @Schema(description = "操作时间戳")
    private LocalDateTime createTime;

    @Schema(description = "操作人ID")
    private Long operatorId;

    @Schema(description = "操作人角色(枚举code)")
    private OperatorRoleEnum operatorRole;

    @Schema(description = "链路追踪ID(字符串)")
    private String traceId;

    @Schema(description = "操作模块")
    private OperationModuleEnum module;

    @Schema(description = "操作对象类型(枚举code)")
    private OperationTargetTypeEnum targetType;

    @Schema(description = "操作对象ID")
    private Long targetId;

    @Schema(description = "操作类型(枚举code)")
    private OperationActionEnum action;

    @Schema(description = "操作描述")
    private String description;

    @Schema(description = "操作结果 0-成功 1-失败")
    private Integer result;

    @Schema(description = "失败原因摘要")
    private String errorMsg;

    @Schema(description = "设备ID(仅USER)")
    private Long deviceId;

    @Schema(description = "请求URI")
    private String requestUri;

    @Schema(description = "请求方法")
    private String requestMethod;

    @Schema(description = "接口耗时(ms)")
    private long durationMs;
}
