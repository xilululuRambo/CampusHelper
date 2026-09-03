package com.rambo.module.notification.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum IsReadStatus {
    UNREAD(0, EnumConstants.IS_READ_STATUS_UNREAD),
    READ(1, EnumConstants.IS_READ_STATUS_READ);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String description;

    IsReadStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
