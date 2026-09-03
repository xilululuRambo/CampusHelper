package com.rambo.module.admin.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

/**
 * 管理员状态（账号可用性维度，与角色 AdminRole 正交）
 */
@Getter
public enum AdminStatus {
    NORMAL(0, EnumConstants.ADMIN_STATUS_NORMAL),
    DISABLED(1, EnumConstants.ADMIN_STATUS_DISABLED);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String description;

    AdminStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
