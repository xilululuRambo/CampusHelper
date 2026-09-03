package com.rambo.module.task.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum TaskApplyStatus {
    // 0-待处理 1-已接受 2-已拒绝 3-已完成 4-已取消
    PENDING_APPLICATION(0, EnumConstants.PENDING_APPLICATION),
    ACCEPTED_APPLICATION(1, EnumConstants.ACCEPTED_APPLICATION),
    REJECTED_APPLICATION(2, EnumConstants.REJECTED_APPLICATION),
    COMPLETED_APPLICATION(3, EnumConstants.COMPLETED_APPLICATION),
    CANCELLED_APPLICATION(4, EnumConstants.CANCELLED_APPLICATION);

    @EnumValue
    private final Integer code;
    private final String desc;

    TaskApplyStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}