package com.rambo.infrastructure.web;

import com.rambo.common.constants.WebPathConstants;
import com.rambo.infrastructure.auth.AdminInterceptor;
import com.rambo.infrastructure.auth.AuthInterceptor;
import com.rambo.infrastructure.auth.JwtInterceptor;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 基础设施层 MVC 拦截器注册（鉴权链路前半段：JWT 解析 → 登录态校验 → 管理员校验）。
 * 学生认证拦截器（NoAuthInterceptor）属用户域业务规则，由 {@code module.user} 的
 * {@code UserWebConfig} 以 @Order(2) 注册在鉴权链路之后。
 */
@Configuration
@Order(1)
public class SpringMvcConfig implements WebMvcConfigurer {
    @Resource
    private JwtInterceptor jwtInterceptor;
    @Resource
    private AuthInterceptor authInterceptor;
    @Resource
    private AdminInterceptor adminInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        WebPathConstants.EXCLUDE_PATHS
                );
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        WebPathConstants.EXCLUDE_PATHS
                );
        registry.addInterceptor(adminInterceptor)
                .addPathPatterns("/admin/**")
                .excludePathPatterns(
                        WebPathConstants.EXCLUDE_PATHS
                );
    }
}
