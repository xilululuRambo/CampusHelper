package com.rambo.module.operationlog.annotation;

import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 操作日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Log {
    // 操作模块
    OperationModuleEnum module();
    // 操作对象类型
    OperationTargetTypeEnum targetType();
    // 操作对象ID
    String targetIdEL();
    // 操作类型
    OperationActionEnum action();
    // 操作描述
    String descriptionEL() default "";
}
