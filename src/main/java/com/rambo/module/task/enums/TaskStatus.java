package com.rambo.module.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum TaskStatus {
    // 0-待接单 1-进行中 2-待确认 3-已完成 4-已取消
    PENDING(0, EnumConstants.PENDING),
    IN_PROGRESS(1, EnumConstants.IN_PROGRESS),
    WAITING_CONFIRM(2, EnumConstants.WAITING_CONFIRM),
    COMPLETED(3, EnumConstants.COMPLETED),
    CANCELLED(4, EnumConstants.CANCELLED);

    @EnumValue
    private final Integer code;
    private final String desc;

    TaskStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
