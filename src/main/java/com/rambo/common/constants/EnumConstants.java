package com.rambo.common.constants;

public class EnumConstants {
    // 用户账号状态：0-正常 1-禁用
    // 状态
    public static final String NORMAL = "正常";
    public static final String DISABLED = "禁用";

    // 学生认证状态：0-未认证 1-已认证
    public static final String USER_AUTH_UNVERIFIED = "未认证";
    public static final String USER_AUTH_VERIFIED = "已认证";

    // 0-待接单 1-进行中 2-待确认 3-已完成 4-已取消
    //任务状态
    public static final String PENDING = "待接受";
    public static final String IN_PROGRESS = "进行中";
    public static final String WAITING_CONFIRM = "待确认完成";
    public static final String COMPLETED = "已完成";
    public static final String CANCELLED = "已取消";

    // 0-待处理 1-已接受 2-已拒绝 3-已完成 4-已取消
    // 任务申请状态
    public static final String PENDING_APPLICATION = "待处理";
    public static final String ACCEPTED_APPLICATION = "已接受";
    public static final String REJECTED_APPLICATION = "已拒绝";
    public static final String COMPLETED_APPLICATION = "已完成";
    public static final String CANCELLED_APPLICATION = "已取消";

    // 0-普通地址 1-默认地址
    //地址状态
    public static final String DEFAULT_ADDRESS = "默认地址";
    public static final String NORMAL_ADDRESS = "普通地址";


    // 商品状态：商品状态：0-在售，1-交易中，2-下架，3-已售出
    public static final String GOODS_STATUS_NORMAL = "在售";
    public static final String GOODS_STATUS_TRADING = "交易中";
    public static final String GOODS_STATUS_DISABLED = "下架";
    public static final String GOODS_STATUS_SOLD_OUT = "已售出";

    // 商品订单状态：0-待付款，1-待发货，2-待收货，3-已完成 ，4-已取消
    public static final String ORDER_STATUS_PENDING_PAYMENT = "待付款";
    public static final String ORDER_STATUS_PENDING_SHIP = "待发货";
    public static final String ORDER_STATUS_PENDING_CONFIRM = "待收货";
    public static final String ORDER_STATUS_COMPLETED = "已完成";
    public static final String ORDER_STATUS_CANCELLED = "已取消";

    // 状态：0-待重试 1-重试成功 2-重试失败终止
    public static final String RETRY_STATUS_PENDING = "待重试";
    public static final String RETRY_STATUS_SUCCESS = "重试成功";
    public static final String RETRY_STATUS_FAILED = "重试失败终止";


    //"通知类型：1-任务新申请，2-申请被同意，3-申请被拒绝，4-任务交付待确认，5-任务确认完成，" +
    //"6-任务完成，7-收到任务评价，8-商品被下单，9-商品已付款，10-商品已发货,11-交易完成,12-收到商品评价"
    public static final String NOTIFICATION_TYPE_TASK_APPLICATION = "任务新申请";
    public static final String NOTIFICATION_TYPE_APPLICATION_ACCEPTANCE = "申请被同意";
    public static final String NOTIFICATION_TYPE_APPLICATION_REJECTION = "申请被拒绝";
    public static final String NOTIFICATION_TYPE_TASK_DELIVERY = "任务交付待确认";
    public static final String NOTIFICATION_TYPE_TASK_CONFIRMATION = "任务确认完成";
    public static final String NOTIFICATION_TYPE_TASK_COMPLETION = "任务完成";
    public static final String NOTIFICATION_TYPE_TASK_EVALUATION = "收到任务评价";
    public static final String NOTIFICATION_TYPE_TASK_CANCEL = "任务已取消";
    public static final String NOTIFICATION_TYPE_GOOD_ORDER = "商品被下单";
    public static final String NOTIFICATION_TYPE_GOOD_PAYMENT = "商品已付款";
    public static final String NOTIFICATION_TYPE_GOOD_DELIVER = "商品已发货";
    public static final String NOTIFICATION_TYPE_TRADE_COMPLETE = "交易完成";
    public static final String NOTIFICATION_TYPE_GOOD_EVALUATION = "收到商品评价";
    public static final String NOTIFICATION_TYPE_POINTS_ADJUST = "积分调整";
    public static final String NOTIFICATION_TYPE_CREDIT_ADJUST = "信誉分调整";
    public static final String NOTIFICATION_TYPE_GOODS_DISABLED = "商品被下架";
    public static final String NOTIFICATION_TYPE_GOODS_ORDER_CANCEL = "订单被取消";
    public static final String ADMIN_NOTIFICATION_TYPE_TASK_CANCEL = "你发布的任务已经被管理员取消";
    public static final String ADMIN_NOTIFICATION_TYPE_TASK_CANCEL_APPLICATION = "你申请的任务已经被管理员取消";
    public static final String ADMIN_NOTIFICATION_TYPE_GOODS_OFF_SHELF = "你发布的商品已经被管理员下架";
    public static final String ADMIN_NOTIFICATION_TYPE_GOODS_OFF_SHELF_BUYER = "你购买的商品已被管理员下架，请留意订单状态";

    // 文本消息 图片消息
    public static final String MESSAGE_TYPE_TEXT = "文本消息";
    public static final String MESSAGE_TYPE_IMAGE = "图片消息";

    // 未读 1-已读
    public static final String IS_READ_STATUS_UNREAD = "未读";
    public static final String IS_READ_STATUS_READ = "已读";

    // 会话状态
    public static final String SESSION_STATUS_OPEN = "打开";
    public static final String SESSION_STATUS_CLOSED = "关闭";

    // 管理员状态
    public static final String ADMIN_STATUS_NORMAL = "正常";
    public static final String ADMIN_STATUS_DISABLED = "禁用";

    // 管理员角色
    public static final String ADMIN_ROLE_SUPER = "超级管理员";
    public static final String ADMIN_ROLE_NORMAL = "普通管理员";

    // token 中的角色：普通用户   管理员   超级管理员角色
    public static final String ROLE_USER = "USER";
    public static final String ROLE_ADMIN = "ADMIN";
    public static final String ROLE_SUPER_ADMIN = "SUPER_ADMIN";

    // ==================== 日志 ====================
    // role角色
    public static final String USER_ROLE = "用户";
    public static final String ADMIN_ROLE = "管理员";
    public static final String SUPER_ADMIN_ROLE = "超级管理员";
    public static final String ANONYMOUS_ROLE = "匿名用户";

    // target目标
    public static final String NONE_TARGET = "无";
    public static final String ADMIN_TARGET = "管理员";
    public static final String USER_TARGET = "用户";
    public static final String TASK_TARGET = "任务";
    public static final String GOODS_TARGET = "商品";
    public static final String ORDER_TARGET = "订单";
    public static final String CATEGORY_TARGET = "商品分类";
    public static final String EVALUATION_TARGET = "商品评价";
    public static final String ADDRESS_TARGET = "收货地址";
    public static final String APPLICATION_TARGET = "任务申请";

    // module模块
    public static final String ADMIN_MODULE = "管理员";
    public static final String AUTH_MODULE = "认证";
    public static final String USER_MODULE = "用户";
    public static final String TASK_MODULE = "任务";
    public static final String GOODS_MODULE = "商品";
    public static final String SYSTEM_MODULE = "系统";
    public static final String NOTIFICATION_MODULE = "通知";
    public static final String CHAT_MODULE = "聊天";
    public static final String SEARCH_MODULE = "搜索";




}
