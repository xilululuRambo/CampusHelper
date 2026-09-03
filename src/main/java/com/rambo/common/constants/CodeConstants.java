package com.rambo.common.constants;

public class CodeConstants {

    // 成功
    public static final Integer SUCCESS = 200;

    // 失败/服务器错误
    public static final Integer FAIL = 500;

    // 参数错误
    public static final Integer PARAM_ERROR = 400;

    // 未登录 /  token 无效
    public static final Integer UNAUTHORIZED = 401;

    // 权限不足
    public static final Integer FORBIDDEN = 403;

    // 资源不存在
    public static final Integer NOT_FOUND = 404;

    // 请求方式错误（GET/POST 不匹配）
    public static final Integer METHOD_NOT_ALLOWED = 405;

    // 业务异常（如用户名已存在、余额不足）
    public static final Integer BUSINESS_ERROR = 600;

    // 接口限流 / 访问频繁
    public static final Integer RATE_LIMIT = 429;

}