package com.rambo.infrastructure.auth;

import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.context.RoleHolder;
import com.rambo.common.exception.BusinessException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 管理员授权拦截器：只做角色校验，不解析 token。
 * 身份上下文（IdHolder / RoleHolder）已由 JwtInterceptor 统一建立，
 * 因此本拦截器不再依赖 Redis 黑名单、设备 Hash、token 刷新等用户侧逻辑。
 */
@Component
public class AdminInterceptor implements HandlerInterceptor {

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
        // 校验当前身份是否为管理员（身份已由 JwtInterceptor 建立，非管理员或未登录都会被拒绝）
        if (!EnumConstants.ROLE_ADMIN.equals(RoleHolder.getRole())
                && !EnumConstants.ROLE_SUPER_ADMIN.equals(RoleHolder.getRole())) {
            throw new BusinessException(MessageConstants.UNAUTHORIZED);
        }
        return true;
    }
}
