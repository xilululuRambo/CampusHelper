package com.rambo.unit;

import com.rambo.common.utils.RegexUtil;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 正则工具单元测试：手机号/验证码/邮箱/用户名/账号/密码/姓名。
 * 覆盖合法输入、非法输入与边界值。
 */
class RegexUtilTest {

    // ==================== 手机号 ====================

    @Test
    @DisplayName("手机号：合法 1[3-9] 开头 11 位")
    void phone_valid() {
        assertThat("13912345678").matches(RegexUtil.REGEX_PHONE);
        assertThat("15012345678").matches(RegexUtil.REGEX_PHONE);
        assertThat("19912345678").matches(RegexUtil.REGEX_PHONE);
    }

    @Test
    @DisplayName("手机号：非法（长度/号段/字符）")
    void phone_invalid() {
        assertThat("12345678901").doesNotMatch(RegexUtil.REGEX_PHONE);   // 非 1[3-9]
        assertThat("1391234567").doesNotMatch(RegexUtil.REGEX_PHONE);    // 10 位
        assertThat("139123456789").doesNotMatch(RegexUtil.REGEX_PHONE);  // 12 位
        assertThat("1391234567a").doesNotMatch(RegexUtil.REGEX_PHONE);   // 含字母
    }

    // ==================== 验证码 ====================

    @Test
    @DisplayName("验证码：6 位数字")
    void code6_valid() {
        assertThat("123456").matches(RegexUtil.REGEX_CODE_6);
        assertThat("000000").matches(RegexUtil.REGEX_CODE_6);
    }

    @Test
    @DisplayName("验证码：6 位数字非法输入")
    void code6_invalid() {
        assertThat("12345").doesNotMatch(RegexUtil.REGEX_CODE_6);
        assertThat("1234567").doesNotMatch(RegexUtil.REGEX_CODE_6);
        assertThat("abcdef").doesNotMatch(RegexUtil.REGEX_CODE_6);
        assertThat("123 56").doesNotMatch(RegexUtil.REGEX_CODE_6);
    }

    // ==================== 邮箱 ====================

    @Test
    @DisplayName("邮箱：合法格式")
    void email_valid() {
        assertThat("user@example.com").matches(RegexUtil.REGEX_EMAIL);
        assertThat("a_b-c@mail.school.edu.cn").matches(RegexUtil.REGEX_EMAIL);
    }

    @Test
    @DisplayName("邮箱：非法格式")
    void email_invalid() {
        assertThat("user@").doesNotMatch(RegexUtil.REGEX_EMAIL);
        assertThat("@example.com").doesNotMatch(RegexUtil.REGEX_EMAIL);
        assertThat("user@@example.com").doesNotMatch(RegexUtil.REGEX_EMAIL);
        assertThat("user example.com").doesNotMatch(RegexUtil.REGEX_EMAIL);
    }

    // ==================== 用户名 ====================

    @Test
    @DisplayName("用户名：4-15 位字母数字下划线")
    void username_valid() {
        assertThat("abcd").matches(RegexUtil.REGEX_USERNAME);
        assertThat("user_123").matches(RegexUtil.REGEX_USERNAME);
        assertThat("123456789012345").matches(RegexUtil.REGEX_USERNAME); // 15 位边界
    }

    @Test
    @DisplayName("用户名：越界/非法字符")
    void username_invalid() {
        assertThat("abc").doesNotMatch(RegexUtil.REGEX_USERNAME);          // 3 位
        assertThat("abcdefghijklmnop").doesNotMatch(RegexUtil.REGEX_USERNAME); // 16 位
        assertThat("user-name").doesNotMatch(RegexUtil.REGEX_USERNAME);    // 连字符
        assertThat("用 户 名").doesNotMatch(RegexUtil.REGEX_USERNAME);
    }

    // ==================== 账号（管理员） ====================

    @Test
    @DisplayName("账号：11 位数字")
    void account_valid() {
        assertThat("13900000001").matches(RegexUtil.REGEX_ACCOUNT);
    }

    @Test
    @DisplayName("账号：非 11 位数字被拒")
    void account_invalid() {
        assertThat("1390000000").doesNotMatch(RegexUtil.REGEX_ACCOUNT);
        assertThat("139000000011").doesNotMatch(RegexUtil.REGEX_ACCOUNT);
        assertThat("1390000000a").doesNotMatch(RegexUtil.REGEX_ACCOUNT);
    }

    // ==================== 密码 ====================

    @Test
    @DisplayName("密码：6-20 位且必须同时含字母与数字")
    void password_valid() {
        assertThat("abc123").matches(RegexUtil.REGEX_PASSWORD);
        assertThat("admin123456").matches(RegexUtil.REGEX_PASSWORD);
        assertThat("a1b2c3d4e5f6g7h8i9j0").matches(RegexUtil.REGEX_PASSWORD); // 20 位边界
    }

    @Test
    @DisplayName("密码：纯字母/纯数字/越界被拒")
    void password_invalid() {
        assertThat("abcdef").doesNotMatch(RegexUtil.REGEX_PASSWORD);        // 纯字母
        assertThat("123456").doesNotMatch(RegexUtil.REGEX_PASSWORD);        // 纯数字
        assertThat("ab12").doesNotMatch(RegexUtil.REGEX_PASSWORD);          // 4 位
        assertThat("abc123def456ghi789jkl0").doesNotMatch(RegexUtil.REGEX_PASSWORD); // 21 位
        assertThat("abc 123").doesNotMatch(RegexUtil.REGEX_PASSWORD);       // 含空格
    }

    // ==================== 姓名 ====================

    @Test
    @DisplayName("姓名：2-4 位中文")
    void name_valid() {
        assertThat("张三").matches(RegexUtil.REGEX_NAME);
        assertThat("欧阳娜娜").matches(RegexUtil.REGEX_NAME);
    }

    @Test
    @DisplayName("姓名：非中文/长度越界被拒")
    void name_invalid() {
        assertThat("张").doesNotMatch(RegexUtil.REGEX_NAME);
        assertThat("张三四五六").doesNotMatch(RegexUtil.REGEX_NAME);
        assertThat("Zhang").doesNotMatch(RegexUtil.REGEX_NAME);
    }

    // ==================== 通用 ====================

    @Test
    @DisplayName("正则常量均为可编译的 Pattern（防手滑写坏）")
    void allRegexes_compilable() {
        Pattern.compile(RegexUtil.REGEX_PHONE);
        Pattern.compile(RegexUtil.REGEX_CODE_4);
        Pattern.compile(RegexUtil.REGEX_CODE_6);
        Pattern.compile(RegexUtil.REGEX_EMAIL);
        Pattern.compile(RegexUtil.REGEX_USERNAME);
        Pattern.compile(RegexUtil.REGEX_ACCOUNT);
        Pattern.compile(RegexUtil.REGEX_PASSWORD);
        Pattern.compile(RegexUtil.REGEX_NAME);
    }
}
