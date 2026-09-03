package com.rambo.infrastructure.web;

import cn.hutool.core.lang.UUID;
import jakarta.servlet.*;
import org.slf4j.MDC;

import java.io.IOException;

public class TraceIdFilter implements Filter {
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse, FilterChain filterChain) throws IOException, ServletException {
        try {
            //traceId 用于日志关联
            String traceId = UUID.randomUUID().toString().replace("-", "");

            //添加到MDC中(MDC是个ThreadLocal，用于存储线程安全的变量)
            MDC.put("traceId", traceId);

            //继续执行下一个过滤器或业务方法
            filterChain.doFilter(servletRequest, servletResponse);
        }finally{
            //确保在finally中移除traceId，避免泄漏
            MDC.remove("traceId");
        }
    }
}
