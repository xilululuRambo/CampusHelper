package com.rambo.common.constants;

public class PrefixConstants {
    // 验证码前缀
    public static final String CODE_PREFIX = "code:";
    // 验证码冷却时间前缀
    public static final String CODE_COOLDOWN_TIME_PREFIX = "code_cooldown_time:";

    // 任务同意拒绝取消完成锁前缀
    //用同一把锁保证同意拒绝取消完成操作的原子斥性
    public static final String TASK_AGREE_LOCK_PREFIX = "lock:task:agree_reject_cancel_complete:";

    // 商品购买锁前缀
    public static final String GOODS_BUY_LOCK_PREFIX = "lock:goods:buy:";
    // 商品订单锁前缀
    public static final String GOODS_ORDER_LOCK_PREFIX = "lock:goods:order:";


    // 商品索引名
    public static final String GOODS_INDEX = "goods_index";
    // 商品类型
    public static final String GOODS_TYPE = "goods";
    // 任务类型
    public static final String TASK_TYPE = "task";
    // 任务索引名
    public static final String TASK_INDEX = "task_index";

    // 任务热门排名索引名
    public static final String TASK_RANK_TOTAL = "task_rank:total";
    // 任务热门排名索引名
    public static final String TASK_RANK_MONTH = "task_rank:month:";
    // 任务关键词排名索引名
    public static final String HOT_KEYWORDS = "hot:keywords";
    // 任务关键词归档索引名前缀（后缀为 yyyy-MM-dd 归属日期，收编孤儿时可还原数据真实日期）
    public static final String HOT_KEYWORDS_ARCHIVE = "hot:keywords:archive:";
    // 任务热门排名归档索引名
    public static final String TASK_RANK_MONTH_ARCHIVE = "task_rank:month:archive";
    // accessToken 黑名单前缀
    public static final String AT_BLACKLIST = "at_blacklist:";
    // 用户账号禁用标记前缀（无状态 JWT 无法逐个拉黑 AT，禁用期间用服务端权威标记拒绝，解禁时删除）
    public static final String USER_DISABLED = "user_disabled:";
    // 用户认证/账号状态快照前缀（NoAuthInterceptor 用，值格式 "authStatus:status"，
    // 认证成功与管理员禁用/启用时删除，另有 TTL 兜底过期）
    public static final String USER_STATUS = "user_status:";
    // 管理员账号禁用标记前缀（与用户侧同语义：禁用即拒绝旧 AT，解禁时删除）
    public static final String ADMIN_DISABLED = "admin_disabled:";
    // 用户设备 Refresh Token Hash key 前缀
    public static final String USER_TOKENS = "user_tokens:";
    // 登录失败计数前缀（5 次锁定 15 分钟）
    public static final String ADMIN_LOGIN_FAIL = "admin_login_fail:";
    // 管理员 Refresh Token Hash key 前缀
    public static final String ADMIN_TOKENS = "admin_tokens:";
    // Refresh Token 刷新锁前缀
    public static final String REFRESH_LOCK = "refresh_lock:";
    // 管理员 Refresh Token 刷新锁前缀（与用户锁隔离，避免 admin/user 表 id 冲突）
    public static final String ADMIN_REFRESH_LOCK = "refresh_lock:admin:";
    // 超时关单 Job 分布式锁（防止 XXL-JOB 多执行器/重试并发触发重复关单）
    public static final String GOODS_ORDER_TIMEOUT_LOCK = "lock:goods:order:timeout";
}
