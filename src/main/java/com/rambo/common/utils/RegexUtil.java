package com.rambo.common.utils;

/**
 * 正则表达式工具类
 */
public class RegexUtil {

    // 私有构造，禁止实例化
    private RegexUtil() {
    }

    /**
     * 中国大陆手机号正则
     */
    public static final String REGEX_PHONE = "^1[3-9]\\d{9}$";

    /**
     * 4位数字验证码
     */
    public static final String REGEX_CODE_4 = "^\\d{4}$";

    /**
     * 6位数字验证码
     */
    public static final String REGEX_CODE_6 = "^\\d{6}$";

    /**
     * 邮箱正则
     */
    public static final String REGEX_EMAIL = "^[a-zA-Z0-9_-]+@[a-zA-Z0-9_-]+(\\.[a-zA-Z0-9_-]+)+$";

    /**
     * 用户名：4-15位，字母数字下划线（允许数字开头——注册自动生成的是随机字母数字串，
     * 需与格式校验规则保持一致；上限 15 与 DB 字段 varchar(15) 对齐）
     */
    public static final String REGEX_USERNAME = "^\\w{4,15}$";

    /**
     * 账号：11位数字
     */
    public static final String REGEX_ACCOUNT = "^\\d{11}$";

    /**
     * 密码：6-20位，字母+数字
     */
    public static final String REGEX_PASSWORD = "^(?=.*[A-Za-z])(?=.*\\d)[A-Za-z\\d]{6,20}$";

    /**
     * 姓名：2-4位，中文
     */
    public static final String REGEX_NAME = "^[\\u4e00-\\u9fa5]{2,4}$";


}