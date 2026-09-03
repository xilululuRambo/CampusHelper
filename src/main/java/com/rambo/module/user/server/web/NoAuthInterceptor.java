package com.rambo.module.user.server.web;

import com.rambo.common.annotation.NoAuthAnnotation;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.context.IdHolder;
import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.enums.UserStatus;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.concurrent.TimeUnit;

/**
 * 学生认证拦截器（业务层）：用户认证状态/账号状态校验属于用户域业务规则，
 * 从基础设施层下沉到用户模块，使基础设施层对业务模块保持零依赖。
 * 由 {@link UserWebConfig} 注册到 MVC 拦截器链。
 *
 * <p>状态来源：Redis 快照（{@code user_status:{id}}，值格式 {@code authStatus:status}），
 * cache-aside 模式——miss 回源 t_user 并回写；认证成功、管理员禁用/启用时主动删除快照，
 * 另有 TTL 兜底过期，避免每请求一次 SELECT * FROM t_user。</p>
 */
@Component
@Slf4j
public class NoAuthInterceptor implements HandlerInterceptor {

    /** 状态快照 TTL（分钟）：正常由变更点主动失效，TTL 仅作兜底 */
    private static final long STATUS_CACHE_TTL_MINUTES = 30;

    @Resource
    private UserService userService;
    @Resource
    private CacheClient cacheClient;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {

        // OPTIONS 预检请求直接放行（无鉴权头）
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }

        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 1. 有 @NoAuthAnnotation → 登录即可访问（不用学生认证）
        if (handlerMethod.getMethodAnnotation(NoAuthAnnotation.class) != null) {
            return true;
        }

        // 2. 没有 → 必须 登录 + 学生认证 才能访问
        Long userId = IdHolder.getId();
        if (userId == null) {
            // 未登录（JwtInterceptor 放行了匿名请求）
            throw new BusinessException(MessageConstants.NOT_AUTH);
        }

        UserAuthStatus authStatus = null;
        UserStatus status = null;

        // 2.1 读 Redis 快照："authStatus:status"（如 VERIFIED:NORMAL）
        String cached = cacheClient.get(PrefixConstants.USER_STATUS + userId);
        if (cached != null) {
            int sep = cached.indexOf(':');
            if (sep > 0) {
                try {
                    authStatus = UserAuthStatus.valueOf(cached.substring(0, sep));
                    status = UserStatus.valueOf(cached.substring(sep + 1));
                } catch (IllegalArgumentException e) {
                    // 快照损坏（枚举值不识别），回源数据库重建
                    log.warn("用户状态快照格式异常，回源重建：userId={}，value={}", userId, cached);
                }
            }
        }

        // 2.2 快照未命中/损坏 → 回源 t_user 并回写（字段缺失时不回写，保持直查语义）
        if (authStatus == null || status == null) {
            User user = userService.lambdaQuery().eq(User::getId, userId).one();
            if (user == null) {
                throw new BusinessException(MessageConstants.NOT_AUTH);
            }
            authStatus = user.getAuthStatus();
            status = user.getStatus();
            if (authStatus != null && status != null) {
                cacheClient.set(PrefixConstants.USER_STATUS + userId,
                        authStatus.name() + ":" + status.name(),
                        STATUS_CACHE_TTL_MINUTES, TimeUnit.MINUTES);
            }
        }

        // 认证状态校验：只有已认证（authStatus=VERIFIED）用户才能访问业务接口
        if (authStatus != UserAuthStatus.VERIFIED) {
            throw new BusinessException(MessageConstants.NOT_AUTH);
        }
        // 账号状态校验：被禁用用户即使 token 未过期也立即拒绝（拆分后不再依赖认证状态兜底）
        if (status == UserStatus.DISABLED) {
            throw new BusinessException(MessageConstants.USER_DISABLED);
        }

        return true;
    }
}
