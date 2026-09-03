package com.rambo.common.context;

/**
 * 当前请求用户角色上下文（ThreadLocal，由认证拦截器写入，业务层读取）
 */
public class RoleHolder {
    // 用于存储当前线程的用户角色
    static ThreadLocal<String> role = new ThreadLocal<>();

    // 设置当前线程的用户角色
    public static void setRole(String role) {
        RoleHolder.role.set(role);
    }

    // 获取当前线程的用户角色（未建立身份时为 null，判空逻辑由调用方决定）
    public static String getRole() {
        return RoleHolder.role.get();
    }

    // 清除当前线程的用户角色
    public static void clearRole() {
        RoleHolder.role.remove();
    }
}
