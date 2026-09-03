package com.rambo.common.enumType;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum CategoryStatus {
    // 0-正常 1-禁用
    NORMAL(0, EnumConstants.NORMAL),
    DISABLED(1, EnumConstants.DISABLED);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;

    CategoryStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
