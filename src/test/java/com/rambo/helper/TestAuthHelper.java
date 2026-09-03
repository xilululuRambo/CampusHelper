package com.rambo.helper;

import cn.hutool.core.util.RandomUtil;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.infrastructure.auth.JwtUtil;
import com.rambo.module.user.pojo.entity.Student;
import com.rambo.module.user.server.service.StudentService;
import jakarta.annotation.Resource;
import org.springframework.core.env.Environment;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * 测试登录助手 —— 零侵入获取验证码：
 * 项目把验证码写入 Redis（sendCode 后、login 校验并删除前），
 * 因此测试直接调真实 sendCode 接口，再从 Redis 读出验证码完成登录，无需 mock 短信。
 *
 * 注意：实际端口必须通过 Environment 延迟读取，不能 @Value("${local.server.port}")——
 * 该属性在 Web 服务器启动后才写入 Environment，bean 创建阶段解析会失败。
 */
@Component
public class TestAuthHelper {

    @Resource
    private StringRedisTemplate redis;

    @Resource
    private JwtUtil jwtUtil;

    @Resource
    private StudentService studentService;

    @Resource
    private Environment environment;

    private final RestTemplate rest = new RestTemplate();

    private int port() {
        return Integer.parseInt(environment.getProperty("local.server.port"));
    }

    private String baseUrl() {
        return "http://localhost:" + port() + "/api";
    }

    /**
     * 注册 + 登录，返回带 token 的用户身份（未做学生认证）。
     * 每个用例调用都会生成全新手机号，天然隔离，无需清理。
     */
    public AuthUser registerAndLogin() {
        String phone = "139" + RandomUtil.randomNumbers(8);

        // 1. 发验证码（真实接口）
        ResponseEntity<Map> codeResp = rest.postForEntity(
                baseUrl() + "/user/code?phone=" + phone, null, Map.class);
        assertOk(codeResp, "发送验证码失败: " + phone);

        // 2. 从 Redis 读验证码
        String code = redis.opsForValue().get(PrefixConstants.CODE_PREFIX + phone);
        if (code == null) {
            throw new IllegalStateException("Redis 中未找到验证码，请确认测试库 Redis db1 可用且 sendCode 已落库");
        }

        // 3. 登录（已注册则登录，未注册则自动注册）
        Map<String, Object> body = new HashMap<>();
        body.put("phone", phone);
        body.put("code", code);
        ResponseEntity<Map> loginResp = rest.postForEntity(baseUrl() + "/user/login", body, Map.class);
        assertOk(loginResp, "登录失败: " + phone);

        Map<?, ?> data = (Map<?, ?>) loginResp.getBody().get("data");
        String accessToken = (String) data.get("accessToken");
        String refreshToken = (String) data.get("refreshToken");
        String deviceId = (String) data.get("deviceId");

        // 4. 从 token 解析 userId（login 响应不含 userId，避免多打一次 /user/me）
        Long userId = Long.parseLong(jwtUtil.parseStringClaim(accessToken));

        return new AuthUser(phone, userId, accessToken, refreshToken, deviceId);
    }

    /**
     * 注册 + 登录 + 学生认证（authStatus=VERIFIED）。
     * 业务接口（发布任务/申请/购买等）经 NoAuthInterceptor 要求已认证用户，
     * 每用例插入一条随机学号学生记录再认证，互不干扰（uk_student_id 唯一）。
     */
    public AuthUser registerAndLoginWithAuth() {
        AuthUser user = registerAndLogin();

        // 学号唯一（uk_student_id），随机 6 位仅 10^6 空间，测试库数据累积后撞车概率上升；撞了重新生成
        Student student;
        while (true) {
            student = new Student();
            student.setStudentId("20" + RandomUtil.randomNumbers(6));
            student.setRealName("测试同学");
            student.setMajor("软件工程");
            student.setGrade("2023级");
            student.setCollege("计算机学院");
            try {
                studentService.save(student);
                break;
            } catch (DuplicateKeyException e) {
                // 撞唯一索引，重试下一轮随机学号
            }
        }

        Map<String, String> authBody = Map.of(
                "studentId", student.getStudentId(),
                "realName", "测试同学");
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setBearerAuth(user.getAccessToken());
        ResponseEntity<Map> authResp = rest.postForEntity(
                baseUrl() + "/user/auth", new HttpEntity<>(authBody, headers), Map.class);
        assertOk(authResp, "学生认证失败: " + student.getStudentId());

        return user;
    }

    private void assertOk(ResponseEntity<Map> resp, String msg) {
        if (resp.getBody() == null || !Integer.valueOf(200).equals(resp.getBody().get("code"))) {
            throw new IllegalStateException(msg + "，响应: " + resp.getBody());
        }
    }
}
