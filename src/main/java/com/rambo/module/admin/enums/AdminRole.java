package com.rambo.module.admin.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

/**
 * 管理员角色（身份维度，与状态 AdminStatus 正交）
 */
@Getter
public enum AdminRole {
    SUPER(0, EnumConstants.ADMIN_ROLE_SUPER),
    NORMAL(1, EnumConstants.ADMIN_ROLE_NORMAL);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String description;

    AdminRole(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
