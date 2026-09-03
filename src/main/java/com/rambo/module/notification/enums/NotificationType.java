package com.rambo.module.notification.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import com.rambo.common.constants.EnumConstants;
import lombok.Getter;

@Getter
public enum NotificationType {
    //"通知类型：1-任务新申请，2-申请被同意，3-申请被拒绝，4-任务交付待确认，5-任务确认完成，" +
            //"6-任务完成，7-收到任务评价，8-商品被下单，9-商品已付款，10-商品已发货,11-交易完成,12-收到商品评价"
    TASK_NEW_APPLY(1, EnumConstants.NOTIFICATION_TYPE_TASK_APPLICATION),
    APPLY_AGREE(2, EnumConstants.NOTIFICATION_TYPE_APPLICATION_ACCEPTANCE),
    APPLY_REJECT(3, EnumConstants.NOTIFICATION_TYPE_APPLICATION_REJECTION),
    TASK_DELIVER_CONFIRM(4, EnumConstants.NOTIFICATION_TYPE_TASK_DELIVERY),
    TASK_CONFIRM_COMPLETE(5, EnumConstants.NOTIFICATION_TYPE_TASK_CONFIRMATION),
    TASK_COMPLETE(6, EnumConstants.NOTIFICATION_TYPE_TASK_COMPLETION),
    TASK_EVALUATE(7, EnumConstants.NOTIFICATION_TYPE_TASK_EVALUATION),
    TASK_CANCEL(8, EnumConstants.NOTIFICATION_TYPE_TASK_CANCEL),
    GOODS_ORDER(9, EnumConstants.NOTIFICATION_TYPE_GOOD_ORDER),
    GOODS_PAY(10, EnumConstants.NOTIFICATION_TYPE_GOOD_PAYMENT),
    GOODS_DELIVER(11, EnumConstants.NOTIFICATION_TYPE_GOOD_DELIVER),
    TRADE_COMPLETE(12, EnumConstants.NOTIFICATION_TYPE_TRADE_COMPLETE),
    GOODS_EVALUATE(13, EnumConstants.NOTIFICATION_TYPE_GOOD_EVALUATION),
    POINTS_ADJUST(14, EnumConstants.NOTIFICATION_TYPE_POINTS_ADJUST),
    CREDIT_ADJUST(15, EnumConstants.NOTIFICATION_TYPE_CREDIT_ADJUST),
    GOODS_DISABLED(16, EnumConstants.NOTIFICATION_TYPE_GOODS_DISABLED),
    GOODS_ORDER_CANCEL(17, EnumConstants.NOTIFICATION_TYPE_GOODS_ORDER_CANCEL);

    @EnumValue
    @JsonValue
    private final Integer type;
    private final String description;

    NotificationType(Integer type, String description) {
        this.type = type;
        this.description = description;
    }
}
