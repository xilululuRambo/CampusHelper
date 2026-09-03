package com.rambo.module.user.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum AddressStatus {
    // 0-普通地址 1-默认地址
    NORMAL_ADDRESS(0, EnumConstants.NORMAL_ADDRESS),
    DEFAULT_ADDRESS(1, EnumConstants.DEFAULT_ADDRESS);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;

    AddressStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}