package com.rambo.common.enumType;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum RetryStatus {

    // 状态：0-待重试 1-重试成功 2-重试失败终止
    PENDING(0, EnumConstants.RETRY_STATUS_PENDING),
    SUCCESS(1, EnumConstants.RETRY_STATUS_SUCCESS),
    FAILED(2, EnumConstants.RETRY_STATUS_FAILED);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String description;

    RetryStatus(Integer code, String description) {
        this.code = code;
        this.description = description;
    }
}
