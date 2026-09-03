package com.rambo.infrastructure.auth;

import com.rambo.common.context.IdHolder;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@Slf4j
public class AuthInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // OPTIONS 预检请求直接放行（无鉴权头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        // 非 Controller 方法直接放行（静态资源、Knife4j 内部请求等）
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }

        // 1. 从 ThreadLocal 获取用户ID
        IdHolder.getId();

        // 2. 有用户信息，放行
        return true;
    }
}