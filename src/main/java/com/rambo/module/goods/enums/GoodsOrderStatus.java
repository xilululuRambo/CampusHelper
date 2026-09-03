package com.rambo.module.goods.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum GoodsOrderStatus{
    // 订单状态：0-待付款，1-待发货，2-待收货，3-已完成 ，4-已取消
    PENDING_PAYMENT(0, EnumConstants.ORDER_STATUS_PENDING_PAYMENT),
    PENDING_SHIP(1, EnumConstants.ORDER_STATUS_PENDING_SHIP),
    PENDING_CONFIRM(2, EnumConstants.ORDER_STATUS_PENDING_CONFIRM),
    COMPLETED(3, EnumConstants.ORDER_STATUS_COMPLETED),
    CANCELLED(4, EnumConstants.ORDER_STATUS_CANCELLED);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;

    GoodsOrderStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
