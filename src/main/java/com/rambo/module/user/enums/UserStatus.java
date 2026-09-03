package com.rambo.module.user.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

/**
 * 用户账号状态（与学生认证状态正交，互不影响）
 * 0-正常 1-禁用
 */
@Getter
public enum UserStatus {
    NORMAL(0, EnumConstants.NORMAL),
    DISABLED(1, EnumConstants.DISABLED);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;

    UserStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
