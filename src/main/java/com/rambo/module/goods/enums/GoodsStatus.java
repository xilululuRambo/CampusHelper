package com.rambo.module.goods.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum GoodsStatus{
    // 商品状态：0-在售 1-交易中 2-下架  3-已售出
    NORMAL(0, EnumConstants.GOODS_STATUS_NORMAL),
    // 1-交易中
    TRADING(1, EnumConstants.GOODS_STATUS_TRADING),
    // 2-下架
    DISABLED(2, EnumConstants.GOODS_STATUS_DISABLED),
    // 3-已售出
    SOLD_OUT(3, EnumConstants.GOODS_STATUS_SOLD_OUT);

    @EnumValue
    @JsonValue
    private final Integer code;
    private final String desc;

    GoodsStatus(Integer code, String desc) {
        this.code = code;
        this.desc = desc;
    }
}
