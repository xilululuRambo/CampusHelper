package com.rambo.common.context;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;

/**
 * 当前请求用户ID上下文（ThreadLocal，由认证拦截器写入，业务层读取）
 */
public class IdHolder {
    static ThreadLocal<Long> idThreadLocal = new ThreadLocal<>();

    /**
     * 设置用户ID
     * @param id 用户ID
     */
    public static void setId(Long id) {
        idThreadLocal.set(id);
    }

    /**
     * 获取用户ID
     * @return 用户ID
     */
    public static Long getId() {
        Long id = idThreadLocal.get();
        if (id == null) {
            throw new BusinessException(MessageConstants.UNAUTHORIZED);
        }
        return id;
    }

    /**
     * 获取用户ID（无身份时返回 null，不抛异常）
     * 供审计日志匿名分支使用：公开接口匿名访问时操作人为空，但不应阻断请求。
     */
    public static Long getNullableId() {
        return idThreadLocal.get();
    }

    /**
     * 清除用户ID
     */
    public static void clearId() {
        idThreadLocal.remove();
    }
}
