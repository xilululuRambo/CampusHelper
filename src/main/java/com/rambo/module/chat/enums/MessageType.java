package com.rambo.module.chat.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum MessageType {
    TEXT(0, EnumConstants.MESSAGE_TYPE_TEXT),
    IMAGE(1, EnumConstants.MESSAGE_TYPE_IMAGE);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String description;

    MessageType(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
