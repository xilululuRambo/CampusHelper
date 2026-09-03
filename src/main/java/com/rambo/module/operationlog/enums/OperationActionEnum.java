package com.rambo.module.operationlog.enums;

import com.baomidou.mybatisplus.annotation.EnumValue;
import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;

@Getter
public enum OperationActionEnum {
    GOODS_DISABLE(1001, "强制下架商品"),
    GOODS_ENABLE(1002, "恢复商品上架"),
    TASK_CANCEL(2001, "强制取消任务"),
    USER_CREDIT_ADJUST(3001, "调整用户信用分"),
    ORDER_CANCEL(4001, "取消订单"),
    LOGIN(9001, "登录"),
    LOGOUT(9002, "退出登录"),
    LOGOUT_ALL(9003, "踢出所有设备"),
    LOGOUT_DEVICE(9004, "踢出指定设备"),
    USER_UPDATE_INFO(3002, "修改个人资料"),
    USER_AUTH(3003, "实名认证"),
    ADDRESS_ADD(3004, "新增收货地址"),
    ADDRESS_UPDATE(3005, "修改收货地址"),
    ADDRESS_DELETE(3006, "删除收货地址"),
    USER_VIEW_SELF(3007, "查看个人资料"),
    USER_VIEW_OTHER(3008, "查看他人公开信息"),
    USER_VIEW_STUDENT(3009, "查看学生信息"),
    ADDRESS_VIEW(3010, "查看收货地址"),
    TASK_PUBLISH(2002, "发布任务"),
    TASK_UPDATE(2003, "更新任务"),
    TASK_APPLY(2004, "申请任务"),
    TASK_APPLY_ACCEPT(2005, "同意任务申请"),
    TASK_APPLY_REJECT(2006, "拒绝任务申请"),
    TASK_SUBMIT_COMPLETE(2007, "提交完成证据"),
    TASK_CONFIRM_COMPLETE(2008, "确认完成任务"),
    TASK_ADMIN_CANCEL(2009, "管理员取消任务"),
    TASK_VIEW_MINE(2010, "查看我的任务"),
    APPLICATION_VIEW(2011, "查看任务申请列表"),
    ORDER_VIEW(2012, "查看我的订单"),
    ORDER_CREATE(2013, "生成订单"),
    APPLICATION_CANCEL(2014, "取消任务申请"),
    EVALUATION_ADD(2015, "发布评价"),
    TASK_VIEW_DETAIL(2016, "查看任务详情(公开)"),
    EVALUATION_VIEW(2017, "查看任务评价(公开)"),

    // ===== 商品模块（用户/商家侧）=====
    GOODS_PUBLISH(1003, "发布商品"),
    GOODS_UPDATE(1004, "更新商品"),
    GOODS_STATUS_UPDATE(1005, "更新商品状态"),
    GOODS_STATUS_UPDATE_BY_ADMIN(1006, "管理员更新商品状态"),
    GOODS_DELETE(1007, "删除商品"),
    GOODS_BUY(1008, "购买商品"),
    GOODS_VIEW(1009, "查看商品详情(公开)"),
    GOODS_VIEW_MINE(1010, "查看我的商品"),
    GOODS_ORDER_CREATE(1011, "生成商品订单"),
    GOODS_ORDER_PAY(1012, "支付商品订单"),
    GOODS_ORDER_DELIVERY(1013, "商品订单发货"),
    GOODS_ORDER_CANCEL(1014, "取消商品订单"),
    GOODS_ORDER_CANCEL_BY_ADMIN(1015, "管理员取消商品订单"),
    GOODS_ORDER_CONFIRM(1016, "确认收货商品订单"),
    GOODS_ORDER_DELETE(1017, "删除商品订单"),
    GOODS_ORDER_VIEW_MINE(1018, "查看我的商品订单"),
    GOODS_ORDER_VIEW(1019, "查看商品订单详情"),
    GOODS_EVALUATION_ADD(1020, "发布商品评价"),
    GOODS_EVALUATION_VIEW(1021, "查看商品评价(按订单)"),
    GOODS_CATEGORY_ADD(1022, "新增商品分类"),
    GOODS_CATEGORY_UPDATE(1023, "更新商品分类"),
    GOODS_CATEGORY_DELETE(1024, "删除商品分类"),
    GOODS_EVALUATION_DELETE(1025, "删除商品评价"),

    // ===== 管理员操作（账号 / 用户 / 任务）=====
    ADMIN_USER_ENABLE(1026, "管理员启用/禁用用户"),
    ADMIN_USER_UPDATE(1027, "管理员修改用户"),
    ADMIN_USER_POINTS_ADJUST(1028, "管理员调整用户积分"),
    ADMIN_USER_CREDIT_ADJUST(1029, "管理员调整用户信用分"),
    ADMIN_USER_KICK(1030, "管理员踢下线用户"),
    ADMIN_USER_VIEW(1031, "管理员查看用户详情"),
    ADMIN_ADD(1032, "新增管理员"),
    ADMIN_VIEW(1033, "查看管理员详情"),
    ADMIN_RESET_PASSWORD(1034, "重置管理员密码"),
    ADMIN_UPDATE_INFO(1035, "修改管理员资料"),
    ADMIN_DISABLE(1036, "禁用管理员"),
    TASK_CATEGORY_ADD(1037, "新增任务分类"),
    TASK_CATEGORY_UPDATE(1038, "更新任务分类"),
    TASK_CATEGORY_DELETE(1039, "删除任务分类"),
    TASK_EVALUATION_DELETE(1040, "删除任务评价");

    @EnumValue
    @JsonValue
    private final int code;
    private final String description;

    OperationActionEnum(int code, String description) {
        this.code = code;
        this.description = description;
    }

    public static OperationActionEnum getByCode(int code) {
        for (OperationActionEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return null;
    }
}
