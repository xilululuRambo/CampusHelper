package com.rambo.module.operationlog.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;

import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.MessageConstants;
import lombok.Getter;

@Getter
public enum OperatorRoleEnum {
    USER(0, EnumConstants.USER_ROLE),
    ADMIN(1, EnumConstants.ADMIN_ROLE),
    SUPER_ADMIN(2, EnumConstants.SUPER_ADMIN_ROLE),
    ANONYMOUS(3, EnumConstants.ANONYMOUS_ROLE);

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    OperatorRoleEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OperatorRoleEnum getByCode(int code) {
        for (OperatorRoleEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
