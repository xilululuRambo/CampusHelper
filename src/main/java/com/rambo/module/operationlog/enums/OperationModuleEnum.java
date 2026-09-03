package com.rambo.module.operationlog.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum OperationModuleEnum {
    AUTH(1, EnumConstants.AUTH_MODULE),
    USER(2, EnumConstants.USER_MODULE),
    TASK(3, EnumConstants.TASK_MODULE),
    GOODS(4, EnumConstants.GOODS_MODULE),
    SYSTEM(5, EnumConstants.SYSTEM_MODULE),
    NOTIFICATION(6, EnumConstants.NOTIFICATION_MODULE),
    CHAT(7, EnumConstants.CHAT_MODULE),
    SEARCH(8, EnumConstants.SEARCH_MODULE),
    ADMIN(9, EnumConstants.ADMIN_MODULE);

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    OperationModuleEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OperationModuleEnum getByCode(int code) {
        for (OperationModuleEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
