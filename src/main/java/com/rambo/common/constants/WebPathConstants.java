package com.rambo.common.constants;

import java.util.Arrays;
import java.util.List;

/**
 * Web 层公共路径常量：无需登录即可访问的公开路径白名单。
 * 供基础设施层与业务层的 MVC 拦截器注册共用，避免各配置各自维护同一份清单。
 */
public class WebPathConstants {

    /** 公开路径白名单（登录/验证码/文档/静态资源等无需 token 的接口） */
    public static final List<String> EXCLUDE_PATHS = Arrays.asList(
            "/user/login", "/user/code", "/doc.html", "/doc.html/**",
            "/v3/api-docs/**", "/swagger-ui/**", "/webjars/**",
            "/swagger-resources/**", "/favicon.ico", "/swagger-ui/**", "/admin/login"
    );

    private WebPathConstants() {
    }
}
