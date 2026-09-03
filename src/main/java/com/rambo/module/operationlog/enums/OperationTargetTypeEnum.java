package com.rambo.module.operationlog.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum OperationTargetTypeEnum {
    NONE(0, EnumConstants.NONE_TARGET),
    USER(1, EnumConstants.USER_TARGET),
    TASK(2, EnumConstants.TASK_TARGET),
    GOODS(3, EnumConstants.GOODS_TARGET),
    ORDER(4, EnumConstants.ORDER_TARGET),
    CATEGORY(5, EnumConstants.CATEGORY_TARGET),
    EVALUATION(6, EnumConstants.EVALUATION_TARGET),
    ADDRESS(7, EnumConstants.ADDRESS_TARGET),
    APPLICATION(8, EnumConstants.APPLICATION_TARGET),
    ADMIN(9, EnumConstants.ADMIN_TARGET);

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    OperationTargetTypeEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OperationTargetTypeEnum getByCode(int code) {
        for (OperationTargetTypeEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
