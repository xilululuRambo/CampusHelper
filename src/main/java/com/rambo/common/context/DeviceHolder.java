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
     */
    public static Long getDeviceId() {
        Long deviceId = deviceIdThreadLocal.get();
        if (deviceId == null) {
            throw new BusinessException(MessageConstants.UNAUTHORIZED);
        }
        return deviceId;
    }

    /**
     * 清除设备ID
     */
    public static void clearDeviceId() {
        deviceIdThreadLocal.remove();
    }
}
