package com.rambo.user;

import cn.hutool.core.util.RandomUtil;
import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import com.rambo.module.user.enums.UserAuthStatus;
import com.rambo.module.user.pojo.entity.Student;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.StudentService;
import com.rambo.module.user.server.service.UserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFail;
import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static com.rambo.helper.AssertHelper.assertOkWithData;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 学生认证测试：认证成功 / 重复认证 / 姓名不匹配 / 学号不存在 / 学号已被他人认证 / 未登录。
 *
 * 学号数据不依赖 seed 种子（避免测试多次运行后种子学生被占用），
 * 每个用例实时插入随机学号学生记录，撞唯一索引则重试（与 TestAuthHelper 同策略）。
 */
class UserAuthTest extends BaseApiTest {

    @Resource
    private StudentService studentService;

    @Resource
    private UserService userService;

    /** 插入一条随机学号学生记录，返回 (studentId, realName) */
    private Map<String, String> insertRandomStudent() {
        while (true) {
            String studentId = "20" + RandomUtil.randomNumbers(6);
            String realName = "同学" + RandomUtil.randomString(3);
            Student student = new Student();
            student.setStudentId(studentId);
            student.setRealName(realName);
            student.setMajor("软件工程");
            student.setGrade("2023级");
            student.setCollege("计算机学院");
            try {
                studentService.save(student);
                return Map.of("studentId", studentId, "realName", realName);
            } catch (DuplicateKeyException e) {
                // 学号撞唯一索引，重试
            }
        }
    }

    private ResponseEntity<Map> auth(Map<String, String> body, AuthUser user) {
        return post("/user/auth", body, user);
    }

    // ==================== 认证成功 ====================

    @Test
    @DisplayName("认证：学号+姓名匹配成功，/student/info 可访问且返回本人学号")
    void auth_withValidStudent_success() {
        AuthUser user = newUser();
        Map<String, String> student = insertRandomStudent();

        assertOk(auth(student, user));

        // 认证后 /student/info 返回对应学生信息（VO 不含学号，断言姓名匹配）
        ResponseEntity<Map> info = get("/student/info", user);
        assertOkWithData(info);
        assertThat(String.valueOf(info.getBody().get("data"))).contains(student.get("realName"));

        // /user/me 的 VO 不含 authStatus，认证状态从 DB 验证
        User userDb = userService.lambdaQuery().eq(User::getId, user.getUserId()).one();
        assertThat(userDb.getAuthStatus()).isEqualTo(UserAuthStatus.VERIFIED);
    }

    @Test
    @DisplayName("认证：重复认证被拒（已认证用户不可再次认证）")
    void auth_twice_failed() {
        AuthUser user = newUser();
        Map<String, String> student = insertRandomStudent();

        assertOk(auth(student, user));
        assertFailWithMsg(auth(student, user), "已认证");
    }

    // ==================== 异常与边界 ====================

    @Test
    @DisplayName("认证：真实姓名不匹配被拒")
    void auth_wrongRealName_failed() {
        AuthUser user = newUser();
        Map<String, String> student = insertRandomStudent();

        assertFailWithMsg(auth(Map.of("studentId", student.get("studentId"), "realName", "错误姓名"), user),
                "认证失败");
        // 失败不影响后续正确认证
        assertOk(auth(student, user));
    }

    @Test
    @DisplayName("认证：学号不存在被拒")
    void auth_unknownStudentId_failed() {
        AuthUser user = newUser();
        assertFailWithMsg(auth(Map.of("studentId", "20999999", "realName", "不存在"), user), "认证失败");
    }

    @Test
    @DisplayName("认证：学号已被其他用户认证被拒（学号只能绑定一个账号）")
    void auth_claimedStudent_failed() {
        Map<String, String> student = insertRandomStudent();
        assertOk(auth(student, newUser())); // 用户 A 认证成功

        assertFailWithMsg(auth(student, newUser()), "已被认证");
    }

    @Test
    @DisplayName("认证：未登录被拒")
    void auth_withoutLogin_failed() {
        Map<String, String> student = insertRandomStudent();
        assertFail(auth(student, null));
    }

    @Test
    @DisplayName("认证：学号为空被拒（@Valid）")
    void auth_emptyStudentId_failed() {
        AuthUser user = newUser();
        assertFail(auth(Map.of("realName", "张三"), user));
    }
}
