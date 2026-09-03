package com.rambo.module.operationlog.server.aop;

import com.rambo.module.operationlog.annotation.Log;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.utils.SpelUtil;
import com.rambo.common.context.DeviceHolder;
import com.rambo.common.context.IdHolder;
import com.rambo.common.context.RoleHolder;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import com.rambo.module.operationlog.enums.OperatorRoleEnum;
import com.rambo.module.operationlog.pojo.entity.OperationLog;
import com.rambo.module.operationlog.server.service.IOperationLogService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

import org.springframework.core.annotation.Order;

@Component
@Order(1)
/*
  操作日志切面
  @author Rambo
 */
public class LogAspect {
    @Resource
    private IOperationLogService operationLogService;

    // 操作日志切点
    @Pointcut("@annotation(com.rambo.module.operationlog.annotation.Log)")
    public void logPointcut() {};

    // 操作日志环绕通知
    @Around("@annotation(log)")
    public Object around(ProceedingJoinPoint joinPoint, Log log) throws Throwable {
        // 开始时间戳
        long startTime = System.currentTimeMillis();
        LocalDateTime createTime = LocalDateTime.now();

        // 操作人id（匿名访问时 IdHolder 为 null，置哨兵值 0 而非 null：
        // 雪花ID不可能为0，配合 operator_role=ANONYMOUS 可无歧义区分；
        // 此举免去改动线上 Linux 库的 operator_id 列，规避其 NOT NULL 落库失败）
        Long operatorId = IdHolder.getNullableId();
        if (operatorId == null) {
            operatorId = 0L;
        }

        // 操作人角色（无身份匿名访问时记为 ANONYMOUS，不抛异常）
        String role = RoleHolder.getRole();
        OperatorRoleEnum operatorRole;
        if (role == null) {
            operatorRole = OperatorRoleEnum.ANONYMOUS;
        }else if (role.equals(EnumConstants.ROLE_ADMIN)) {
            operatorRole = OperatorRoleEnum.ADMIN;
        }else if (role.equals(EnumConstants.ROLE_SUPER_ADMIN)) {
            operatorRole = OperatorRoleEnum.SUPER_ADMIN;
        }else {
            operatorRole = OperatorRoleEnum.USER;
        };

        //链路追踪ID
        String traceId = MDC.get("traceId");

        // 操作模块
        OperationModuleEnum module = log.module();

        // 操作对象类型
        OperationTargetTypeEnum targetTypeEnum = log.targetType();

        //操作对象ID
        Long targetId = SpelUtil.parse(joinPoint, log.targetIdEL(), Long.class);

        //操作类型
        OperationActionEnum actionEnum = log.action();

        //操作描述
        String operationDesc = SpelUtil.parse(joinPoint, log.descriptionEL(), String.class);

        // 请求URI和请求方法
        ServletRequestAttributes attributes = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
        String requestUri = null;
        String requestMethod = null;
        if (attributes != null) {
            HttpServletRequest request = attributes.getRequest();
            requestUri = request.getRequestURI();
            requestMethod = request.getMethod();
        }


        Object result;
        int operationResult = 0;
        String errorMsg = "";
        try {
            //执行业务逻辑
            result = joinPoint.proceed();
        } catch (Throwable e) {

            //操作结果
            operationResult = 1;
            //失败原因摘要
            errorMsg = e.getMessage();
            throw e;
        }finally {
            //接口耗时(ms)
            long costTime = System.currentTimeMillis() - startTime;

            OperationLog operationLog = OperationLog.builder()
                    .createTime(createTime)
                    .operatorId(operatorId)
                    .module(module)
                    .targetType(targetTypeEnum)
                    .targetId(targetId)
                    .action(actionEnum)
                    .operatorRole(operatorRole)
                    .traceId(traceId)
                    .description(operationDesc)
                    .result(operationResult)
                    .errorMsg(errorMsg)
                    .requestUri(requestUri)
                    .requestMethod(requestMethod)
                    .durationMs(costTime)
                    .build();

            //设备ID(仅USER)
            if (operatorRole == OperatorRoleEnum.USER) {
                Long deviceId = DeviceHolder.getDeviceId();
                operationLog.setDeviceId(deviceId);
            }
            //日志保存
            operationLogService.saveLog(operationLog);
        }
        return result;
    }
}
