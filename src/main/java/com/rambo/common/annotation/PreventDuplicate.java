package com.rambo.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface PreventDuplicate {
    /**
     * 业务场景标识，防止不同表单间的Token混用
     */
    String scene() default "default";

    /**
     * 请求头中Token的key，默认X-Submit-Token
     */
    String headerName() default "X-Submit-Token";
}