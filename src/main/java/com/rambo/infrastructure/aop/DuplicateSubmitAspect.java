package com.rambo.infrastructure.aop;

import com.rambo.common.annotation.PreventDuplicate;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.constants.RedisKeyConstants;
import com.rambo.common.context.IdHolder;
import com.rambo.common.exception.BusinessException;
import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.Resource;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 防重复提交切面（依赖 Redis 门面 CacheClient，随中间件能力归位至 infrastructure 层）
 */
@Aspect
@Component
@Slf4j
public class DuplicateSubmitAspect {

    @Resource
    private CacheClient cacheClient;
    @Resource
    private HttpServletRequest request;

    @Around("@annotation(pd)")
    public Object around(ProceedingJoinPoint joinPoint, PreventDuplicate pd) throws Throwable {
        // 1. 从请求头中获取Token
        String token = request.getHeader(pd.headerName());
        if (!StringUtils.hasText(token)) {
            log.error("缺少防重复提交令牌，userId={}, scene={}", IdHolder.getId(), pd.scene());
            throw new BusinessException(MessageConstants.SYSTEM_ERROR);
        }

        // 2. 构建Redis Key
        Long userId = IdHolder.getId();
        String key = RedisKeyConstants.SUBMIT_TOKEN_PREFIX + pd.scene() + ":" + userId + ":" + token;

        // 3. 原子删除，校验是否首次提交
        Boolean deleted = cacheClient.delete(key);
        if (!deleted) {
            log.warn("重复提交拦截，userId={}, scene={}, token={}", userId, pd.scene(), token);
            throw new BusinessException(MessageConstants.DUPLICATE_SUBMIT);
        }

        // 4. 放行，执行真正的业务逻辑
        return joinPoint.proceed();
    }
}
