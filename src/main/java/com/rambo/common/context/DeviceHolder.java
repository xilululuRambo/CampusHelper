package com.rambo.common.context;

import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;

/**
 * 当前请求设备ID上下文（ThreadLocal，由认证拦截器写入，业务层读取）
 */
public class DeviceHolder {
    static ThreadLocal<Long> deviceIdThreadLocal = new ThreadLocal<>();

    /**
     * 设置设备ID
     * @param deviceId 设备ID
     */
    public static void setDeviceId(Long deviceId) {
        deviceIdThreadLocal.set(deviceId);
    }

    /**
     * 获取设备ID
     * @return 设备ID
     * @throws BusinessException 无设备上下文时抛出
     */
    public static Long getDeviceId() {
        Long deviceId = deviceIdThreadLocal.get();
        if (deviceId == null) {
            throw new BusinessException(MessageConstants.UNAUTHORIZED);
        }
        return deviceId;
    }

    /**
     * 获取设备ID（无设备上下文时返回 null，不抛异常）
     * 供审计日志使用：Job / MQ 消费 / 内部调用没有 HTTP 设备维度，
     * 审计应如实记为 null，而不是抛异常阻断（乃至顶掉业务原本的异常）。
     */
    public static Long getNullableDeviceId() {
        return deviceIdThreadLocal.get();
    }

    /**
     * 清除设备ID
     */
    public static void clearDeviceId() {
        deviceIdThreadLocal.remove();
    }
}
