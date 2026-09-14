package com.rambo.user;

import com.rambo.BaseApiTest;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFail;
import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static com.rambo.helper.AssertHelper.assertOkWithData;
import static com.rambo.helper.AssertHelper.assertUnauthorized;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 用户模块接口测试：验证码 / 登录注册 / 我的信息 / 学生认证。
 * 认证类用例依赖 student 种子数据（src/test/resources/sql/seed.sql），
 * DDL + 种子就绪前保持 @Disabled。
 */
class UserApiTest extends BaseApiTest {

    // ==================== 发送验证码 /user/code ====================

    @Test
    @DisplayName("发送验证码：合法手机号成功")
    void sendCode_withValidPhone_success() {
        ResponseEntity<Map> resp = post("/user/code?phone=139" + (10000000 + (int) (Math.random() * 89999999)), null, null);
        assertOk(resp);
    }

    @Test
    @DisplayName("发送验证码：非法手机号被拒")
    void sendCode_withInvalidPhone_failed() {
        ResponseEntity<Map> resp = post("/user/code?phone=12345", null, null);
        assertFailWithMsg(resp, "手机号");
    }

    @Test
    @DisplayName("发送验证码：60 秒冷却期内重复请求被拒")
    void sendCode_withinCooldown_failed() {
        String phone = "139" + (10000000 + (int) (Math.random() * 89999999));
        assertOk(post("/user/code?phone=" + phone, null, null));
        // 冷却期内第二次请求必须失败
        ResponseEntity<Map> second = post("/user/code?phone=" + phone, null, null);
        assertFailWithMsg(second, "冷却");
    }

    // ==================== 登录 /user/login ====================

    @Test
    @DisplayName("登录：验证码正确返回双令牌")
    void login_withCorrectCode_returnsTokens() {
        AuthUser user = newUser(); // 内部已走真实 sendCode + login 全流程
        assertThat(user.getAccessToken()).isNotBlank();
        assertThat(user.getRefreshToken()).isNotBlank();
    }

    @Test
    @DisplayName("登录：验证码错误被拒")
    void login_withWrongCode_failed() {
        String phone = "138" + (10000000 + (int) (Math.random() * 89999999));
        post("/user/code?phone=" + phone, null, null);
        Map<String, String> body = Map.of("phone", phone, "code", "000000");
        ResponseEntity<Map> resp = post("/user/login", body, null);
        assertFailWithMsg(resp, "验证码");
    }

    @Test
    @DisplayName("登录：未发验证码直接登录被拒")
    void login_withoutCode_failed() {
        Map<String, String> body = Map.of("phone", "13700000000", "code", "123456");
        ResponseEntity<Map> resp = post("/user/login", body, null);
        assertFailWithMsg(resp, "验证码");
    }

    @Test
    @DisplayName("登录：验证码连续错误达上限后作废，正确验证码也被拒（防暴力枚举）")
    void login_wrongCodeTooManyTimes_invalidatesCode() {
        String phone = "138" + (10000000 + (int) (Math.random() * 89999999));
        assertOk(post("/user/code?phone=" + phone, null, null));
        String realCode = redis.opsForValue().get(PrefixConstants.CODE_PREFIX + phone);
        assertThat(realCode).isNotBlank();
        String wrongCode = "000000".equals(realCode) ? "111111" : "000000";

        // 连续错误尝试：前 N-1 次仅报验证码错误，第 N 次触发作废
        for (int i = 0; i < NumConstants.USER_LOGIN_FAIL_LIMIT; i++) {
            assertFail(post("/user/login", Map.of("phone", phone, "code", wrongCode), null));
        }

        // 验证码已作废：即使输入正确验证码也被拒（尝试次数被限制为每枚验证码 N 次，无法无限枚举）
        assertFailWithMsg(post("/user/login", Map.of("phone", phone, "code", realCode), null), "验证码");

        // 重新获取验证码：新码重置失败计数（跳过 60s 冷却便于用例执行）
        redis.delete(PrefixConstants.CODE_COOLDOWN_TIME_PREFIX + phone);
        assertOk(post("/user/code?phone=" + phone, null, null));
        String newCode = redis.opsForValue().get(PrefixConstants.CODE_PREFIX + phone);
        assertThat(newCode).isNotBlank();

        // 新码试错一次后仍可正常登录（计数已随新码重置，而非沿用旧验证码的失败次数）
        String anotherWrong = newCode.equals("000000") ? "111111" : "000000";
        assertFail(post("/user/login", Map.of("phone", phone, "code", anotherWrong), null));
        assertOk(post("/user/login", Map.of("phone", phone, "code", newCode), null));
    }

    // ==================== 我的信息 /user/me ====================

    @Test
    @DisplayName("我的信息：未登录被拒")
    void me_withoutToken_unauthorized() {
        ResponseEntity<Map> resp = get("/user/me", null);
        assertUnauthorized(resp);
    }

    @Test
    @DisplayName("我的信息：登录后返回本人信息")
    void me_withToken_success() {
        AuthUser user = newUser();
        ResponseEntity<Map> resp = get("/user/me", user);
        assertOkWithData(resp);
        assertThat(String.valueOf(resp.getBody().get("data"))).contains(user.getPhone());
    }

    // ==================== 登出 /user/logout ====================

    @Test
    @DisplayName("登出：成功后 Hash 中该设备的 Refresh Token 被删除")
    void logout_removesRefreshToken() {
        // 注：/user/logout 未标注 @NoAuthAnnotation，需已认证用户（见审查报告 API 评估）
        AuthUser user = newAuthedUser();
        assertOk(get("/user/logout", user));
        // RT 存于 user_tokens:{userId} Hash（field=deviceId），登出应删除该 field
        Boolean exists = redis.opsForHash().hasKey(
                "user_tokens:" + user.getUserId(), user.getDeviceId());
        assertThat(exists).isFalse();
    }

    // ==================== 学生认证 /user/auth（需 DDL + seed 后启用） ====================

    // @Test
    // @DisplayName("学生认证：学号+姓名匹配成功")
    // void auth_withValidStudent_success() {
    //     AuthUser user = newUser();
    //     Map<String, String> body = Map.of("studentId", "2023001", "realName", "张三");
    //     ResponseEntity<Map> resp = post("/user/auth", body, user);
    //     assertOk(resp);
    //     // 认证后 /student/info 可访问
    //     assertOk(get("/student/info", user));
    // }

    // @Test
    // @DisplayName("学生认证：重复认证被拒")
    // void auth_twice_failed() {
    //     AuthUser user = newUser();
    //     Map<String, String> body = Map.of("studentId", "2023001", "realName", "张三");
    //     assertOk(post("/user/auth", body, user));
    //     ResponseEntity<Map> second = post("/user/auth", body, user);
    //     assertFailWithMsg(second, "已认证");
    // }
}
