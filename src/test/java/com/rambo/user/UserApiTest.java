package com.rambo.user;

import com.rambo.BaseApiTest;
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
