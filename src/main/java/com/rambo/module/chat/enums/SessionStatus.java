package com.rambo.module.chat.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum SessionStatus {
    OPEN(0, EnumConstants.SESSION_STATUS_OPEN),
    CLOSED(1, EnumConstants.SESSION_STATUS_CLOSED);

    @JsonValue
    @EnumValue
    private final int code;
    private final String description;

    SessionStatus(int code, String description) {
        this.code = code;
        this.description = description;
    }
}
