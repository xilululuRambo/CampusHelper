package com.rambo.common.constants;

public final class CacheConstants {

    //===================== 用户信息 =====================
    public static final String USER_PRIVATE_INFO = "user:private:info";    // 私有用户信息（按用户ID）
    public static final String USER_PUBLIC_INFO = "user:public:info";    // 公开用户信息（按用户ID）

    //===================== 学生信息 =====================
    public static final String STUDENT_INFO = "student:info";

    //===================== 地址 =====================
    public static final String ADDRESS_LIST = "address:list";    // 地址列表（按用户）

    //===================== 任务分类 =====================
    public static final String TASK_CATEGORY_ALL = "task:category:all";    // 所有分类名称（全局共享，无用户维度）

    //===================== 任务评价 =====================
    public static final String TASK_EVALUATION = "task:evaluation";    // 任务评价列表（按订单ID）

    //===================== 商品 =====================
    public static final String GOODS_DETAIL = "goods:detail";    // 商品详情（按商品ID，纯公开数据无用户视角差异）
}