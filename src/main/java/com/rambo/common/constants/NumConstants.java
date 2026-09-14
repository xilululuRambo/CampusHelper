package com.rambo.common.constants;

import java.util.Arrays;
import java.util.List;

public class NumConstants {
    //设备ID长度
    public static final int DEVICE_ID_LENGTH = 16;
    //验证码长度
    public static final int CODE_LENGTH = 6;
    //验证码过期时间（分钟）
    public static final int CODE_EXPIRE_MINUTES = 5;
    //用户名长度
    public static final int USERNAME_LENGTH = 10;
    //验证码冷却时间（秒）
    public static final int CODE_COOLDOWN_TIME_SECONDS = 60;
    //默认分页大小
    public static final int DEFAULT_PAGE_SIZE = 10;
    //默认分页页码
    public static final int DEFAULT_PAGE_NUM = 1;
    //任务同意锁等待时间（秒）
    public static final int TASK_AGREE_LOCK_WAIT_TIME_SECONDS = 3;
    //全局缓存过期时间（小时）
    public static final int CACHE_EXPIRE_HOURS = 2;
    //提交令牌过期时间（分钟）
    public static final int SUBMIT_TOKEN_EXPIRE_MINUTES = 5;
    //待付款订单过期时间（分钟）
    public static final int PENDING_PAYMENT_EXPIRE_MINUTES = 10;


    //商品图片最大大小（字节）
    public static final long GOODS_IMAGE_MAX_SIZE = 10 * 1024 * 1024L;  // 10MB
    //商品图片最大上传数量
    public static final int GOODS_IMAGE_MAX_COUNT = 9;
    //商品图片允许的类型
    public static final List<String> GOODS_IMAGE_ALLOWED_TYPES = Arrays.asList(".jpg", ".jpeg", ".png", ".gif");
    //头像图片最大大小（字节）
    public static final long AVATAR_IMAGE_MAX_SIZE = 2 * 1024 * 1024L;  // 2MB
    //头像图片允许的类型
    public static final List<String> AVATAR_IMAGE_ALLOWED_TYPES = Arrays.asList(".jpg", ".jpeg", ".png");
    //任务完成证据最大大小（字节）
    public static final long EVIDENCE_MAX_SIZE = 10 * 1024 * 1024L;  // 10MB
    //任务完成证据允许的类型
    public static final List<String> EVIDENCE_ALLOWED_TYPES = Arrays.asList(".jpg", ".jpeg", ".png", ".gif");

    //订单锁等待时间（秒）
    public static final int LOCK_WAIT_TIME_SECONDS = 3;
    //订单锁持有时间（毫秒）
    public static final int LOCK_HOLD_TIME_MILLISECONDS = 3000;
    //订单锁等待时间（毫秒）
    public static final int LOCK_WAIT_TIME_MILLISECONDS = 500;

    //管理员登录失败锁定阈值（次）
    public static final int ADMIN_LOGIN_FAIL_LIMIT = 5;
    //管理员登录失败锁定时间（分钟）
    public static final int ADMIN_LOGIN_LOCK_MINUTES = 15;
    //用户验证码登录失败上限（次，达到即作废当前验证码；与管理员登录共用失败计数机制）
    public static final int USER_LOGIN_FAIL_LIMIT = 5;

    //用户收货地址数量上限
    public static final int ADDRESS_MAX_COUNT = 3;
    //排行榜 Top N 的 ZSet 区间结束下标（0-based，含端点，9 表示取前 10 名）
    public static final int TOP_RANK_END_INDEX = 9;
    //排行榜 Top N 条数（数据库回源 LIMIT 用）
    public static final int TOP_RANK_COUNT = 10;
    //历史消息每页条数上限（Mongo 分页）
    public static final int CHAT_HISTORY_PAGE_SIZE_LIMIT = 100;
    //分页参数下限（pageSize 最小为 1）
    public static final int PAGE_SIZE_MIN = 1;
    //分页参数上限（pageSize 最大为 100，防止深分页拖垮 DB/ES）
    public static final int PAGE_SIZE_MAX = 100;
}
