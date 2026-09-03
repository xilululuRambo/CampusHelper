package com.rambo.common.utils;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

public class SpelUtil {

    private static final ExpressionParser PARSER = new SpelExpressionParser();
    private static final DefaultParameterNameDiscoverer PARAMETER_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

    /**
     * 解析 SpEL 表达式
     *
     * @param joinPoint 切点（用于获取方法参数）
     * @param expression 表达式，比如 "#id" 或 "'用户：' + #name"
     * @param clazz 期望返回的类型
     * @return 解析后的值
     */
    public static <T> T parse(ProceedingJoinPoint joinPoint, String expression, Class<T> clazz) {
        if (expression == null || expression.isEmpty()) {
            return null;
        }

        try {
            // 1. 获取方法签名
            MethodSignature signature = (MethodSignature) joinPoint.getSignature();
            Method method = signature.getMethod();

            // 2. 获取方法参数名数组（如 ["id", "dto"]）
            String[] paramNames = PARAMETER_NAME_DISCOVERER.getParameterNames(method);
            // 3. 获取方法参数值数组
            Object[] args = joinPoint.getArgs();

            // 4. 构建 SpEL 上下文，把参数名和值一一对应塞进去
            EvaluationContext context = new StandardEvaluationContext();
            if (paramNames != null) {
                for (int i = 0; i < paramNames.length; i++) {
                    context.setVariable(paramNames[i], args[i]);
                }
            }

            // 5. 解析表达式，并返回指定类型的结果
            Expression exp = PARSER.parseExpression(expression);
            return exp.getValue(context, clazz);
        } catch (Exception e) {
            // 解析失败时，直接返回 null 或原表达式，防止影响业务
            return null;
        }
    }
}