package com.rambo.module.user.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

/**
 * 学生认证状态（与账号启用/禁用状态正交，互不影响）
 * 0-未认证 1-已认证
 */
@Getter
public enum UserAuthStatus {
    UNVERIFIED(0, EnumConstants.USER_AUTH_UNVERIFIED),
    VERIFIED(1, EnumConstants.USER_AUTH_VERIFIED);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;

    UserAuthStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
