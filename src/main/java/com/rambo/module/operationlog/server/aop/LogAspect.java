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
import com.rambo.infrastructure.database.TransactionUtils;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.annotation.Pointcut;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.LocalDateTime;

import org.springframework.core.annotation.Order;

/*
  操作日志切面
  @author Rambo
 */
@Aspect
@Component
@Order(1)
public class LogAspect {

    // 刻意不用 @Slf4j：本类 around() 的注解参数名就叫 log，
    // Lombok 生成的 log 字段会被该参数遮蔽，编译期即报「找不到符号」。
    private static final Logger LOG = LoggerFactory.getLogger(LogAspect.class);
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
        } finally {
            // 审计属旁路副作用：装配与落库的任何异常都不得影响业务结果。
            // 尤其不能从 finally 抛出——那会顶掉 try 中正在传播的业务异常，
            // 把真实错误改写成审计错误（如无设备上下文时报「未登录」）。
            try {
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

                //设备ID(仅USER)：Job/MQ/内部调用无设备上下文，取 null 而非抛异常
                if (operatorRole == OperatorRoleEnum.USER) {
                    operationLog.setDeviceId(DeviceHolder.getNullableDeviceId());
                }

                // 日志保存：审计记录必须反映「事务最终是否生效」。
                // 本切面标了 @Order(1)，位于事务通知之外，因此：
                //  - 外层方法自身：事务已在 joinPoint.proceed() 内结束，此处无事务上下文 → 立即落库；
                //  - 内层方法（被外层事务包裹）：此处仍有活动事务 → 延迟到 afterCompletion，
                //    若外层事务回滚，则改写为失败记录，避免「业务已回滚、审计却记成功」的假成功。
                // 业务方法自身抛异常时结论已确定（失败），无需再等事务结果。
                if (operationResult == 1) {
                    operationLogService.saveLog(operationLog);
                } else {
                    TransactionUtils.afterCompletion(status -> {
                        if (status == TransactionSynchronization.STATUS_ROLLED_BACK) {
                            operationLog.setResult(1);
                            operationLog.setErrorMsg("外层事务回滚，本次业务变更未生效");
                        }
                        operationLogService.saveLog(operationLog);
                    });
                }
            } catch (Exception e) {
                LOG.error("操作日志记录失败：{}", e.getMessage(), e);
            }
        }
        return result;
    }
}
