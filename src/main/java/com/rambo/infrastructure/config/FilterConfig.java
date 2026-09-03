package com.rambo.infrastructure.config;

import com.rambo.infrastructure.web.TraceIdFilter;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class FilterConfig {
    @Bean
    public FilterRegistrationBean<TraceIdFilter> traceIdFilter() {
        // 配置TraceIdFilter
        FilterRegistrationBean<TraceIdFilter> bean = new FilterRegistrationBean<>();

        // 设置过滤器实例
        bean.setFilter(new TraceIdFilter());

        // 添加URL模式，拦截所有请求
        bean.addUrlPatterns("/*");

        // 设置过滤器优先级，数字越小优先级越高
        bean.setOrder(1);
        return bean;
    }
}
