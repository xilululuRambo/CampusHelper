package com.rambo.module.user.server.web;

import com.rambo.common.constants.WebPathConstants;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 用户模块 MVC 拦截器注册：学生认证拦截器（NoAuthInterceptor）属用户域业务规则，
 * 由业务模块自行注册。@Order(2) 保证其排在基础设施层鉴权拦截器（@Order(1)）之后执行，
 * 即链路为：JWT 解析 → 登录态校验 → 学生认证校验。
 */
@Configuration
@Order(2)
public class UserWebConfig implements WebMvcConfigurer {

    @Resource
    private NoAuthInterceptor noAuthInterceptor;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(noAuthInterceptor)
                .addPathPatterns("/**")
                .excludePathPatterns(
                        WebPathConstants.EXCLUDE_PATHS
                )
                .excludePathPatterns("/admin/**");
    }
}
