package com.rambo.unit;

import com.rambo.common.utils.SpelUtil;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SpEL 解析工具单元测试（Mockito mock 切点，无 Spring 上下文）。
 *
 * 项目依赖 maven-compiler-plugin 的 <parameters>true</parameters>（参数名保留），
 * 否则 #参数名 表达式无法解析——本套用例同时充当该配置的回归守护。
 */
class SpelUtilTest {

    /** 目标方法：用于提供 MethodSignature 与参数（参数名被 -parameters 保留） */
    @SuppressWarnings("unused")
    private String sampleMethod(Long id, String name, Integer score) {
        return id + name + score;
    }

    private ProceedingJoinPoint joinPointOf(Object[] args) throws Exception {
        Method method = SpelUtilTest.class.getDeclaredMethod("sampleMethod", Long.class, String.class, Integer.class);
        MethodSignature signature = mock(MethodSignature.class);
        when(signature.getMethod()).thenReturn(method);

        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.getSignature()).thenReturn(signature);
        when(joinPoint.getArgs()).thenReturn(args);
        return joinPoint;
    }

    @Test
    @DisplayName("解析：单个参数 #id 返回 Long")
    void parse_singleParam() throws Exception {
        Long result = SpelUtil.parse(joinPointOf(new Object[]{42L, "张三", 5}), "#id", Long.class);
        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("解析：字符串参数 #name")
    void parse_stringParam() throws Exception {
        String result = SpelUtil.parse(joinPointOf(new Object[]{42L, "张三", 5}), "#name", String.class);
        assertThat(result).isEqualTo("张三");
    }

    @Test
    @DisplayName("解析：表达式拼接 '用户：' + #name")
    void parse_concatExpression() throws Exception {
        String result = SpelUtil.parse(joinPointOf(new Object[]{42L, "张三", 5}),
                "'用户：' + #name", String.class);
        assertThat(result).isEqualTo("用户：张三");
    }

    @Test
    @DisplayName("解析：算术表达式 #id + #score")
    void parse_arithmeticExpression() throws Exception {
        Long result = SpelUtil.parse(joinPointOf(new Object[]{40L, "张三", 2}),
                "#id + #score", Long.class);
        assertThat(result).isEqualTo(42L);
    }

    @Test
    @DisplayName("解析：空表达式返回 null（不抛异常）")
    void parse_emptyExpression_null() throws Exception {
        assertThat(SpelUtil.parse(joinPointOf(new Object[]{1L, "a", 1}), "", String.class)).isNull();
        assertThat(SpelUtil.parse(joinPointOf(new Object[]{1L, "a", 1}), null, String.class)).isNull();
    }

    @Test
    @DisplayName("解析：非法表达式返回 null（容错不抛异常，日志由调用方负责）")
    void parse_invalidExpression_null() throws Exception {
        assertThat(SpelUtil.parse(joinPointOf(new Object[]{1L, "a", 1}), "### not valid", String.class)).isNull();
        assertThat(SpelUtil.parse(joinPointOf(new Object[]{1L, "a", 1}), "#undefinedVar", String.class)).isNull();
    }

    @Test
    @DisplayName("解析：数值类型自动转换（Long 42 → Integer 42）")
    void parse_numericConversion() throws Exception {
        // SpEL 对数值类型做自动转换，Long#id 可安全转为 Integer
        Integer result = SpelUtil.parse(joinPointOf(new Object[]{42L, "a", 1}), "#id", Integer.class);
        assertThat(result).isEqualTo(42);
    }

    @Test
    @DisplayName("解析：null 参数值返回 null")
    void parse_nullArg_null() throws Exception {
        Long result = SpelUtil.parse(joinPointOf(new Object[]{null, "a", 1}), "#id", Long.class);
        assertThat(result).isNull();
    }
}
