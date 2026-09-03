package com.rambo.admin;

import cn.hutool.core.util.RandomUtil;
import com.rambo.BaseApiTest;
import com.rambo.common.constants.NumConstants;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.infrastructure.auth.JwtUtil;
import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import com.rambo.module.user.enums.UserStatus;
import com.rambo.helper.AuthUser;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.redisson.api.RedissonClient;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员模块测试：登录/失败锁定/越权隔离/me/列表/登出拉黑
 * 种子超管：account=13900000001 password=admin123456
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdminApiTest extends BaseApiTest {

    /** 每次用例前清理登录失败计数，保证套件可重复运行（锁定 key 残留不影响下一轮） */
    @BeforeEach
    void clearLoginFailCounters() {
        var keys = redis.keys("admin_login_fail:*");
        if (keys != null && !keys.isEmpty()) {
            redis.delete(keys);
        }
    }

    private static final String SUPER_ACCOUNT = "13900000001";
    private static final String SUPER_PASSWORD = "admin123456";

    /** 管理员无设备维度，AuthUser.deviceId 固定占位 */
    private static final String DEVICE_ID_PLACEHOLDER = "admin";

    /** 由超管新增的普通管理员（供权限/锁定用例复用） */
    private static String normalAccount;
    private static final String NORMAL_PASSWORD = "abc123456";

    @Resource
    private JwtUtil jwtUtil;

    /** 登录 RT 由 Redisson RMapCache 写入，断言需用同一客户端（StringRedisTemplate 与 RMapCache codec 不兼容） */
    @Resource
    private RedissonClient redissonClient;

    /** 超管登录，返回带 token 的 AuthUser */
    private AuthUser superLogin() {
        Map<String, Object> body = new HashMap<>();
        body.put("account", SUPER_ACCOUNT);
        body.put("password", SUPER_PASSWORD);
        ResponseEntity<Map> resp = post("/admin/login", body, null);
        assertOk(resp);
        return toAuthUser(resp);
    }

    /** 从登录响应构造 AuthUser（id 从 token 解析） */
    private AuthUser toAuthUser(ResponseEntity<Map> resp) {
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        String at = (String) data.get("accessToken");
        String rt = (String) data.get("refreshToken");
        Long id = Long.parseLong(jwtUtil.parseStringClaim(at));
        // 管理员无设备维度，deviceId 固定占位
        return new AuthUser(String.valueOf(id), id, at, rt, DEVICE_ID_PLACEHOLDER);
    }

    @Test
    @Order(1)
    @DisplayName("超管登录：返回双令牌")
    void login_super_success() {
        AuthUser su = superLogin();
        assertThat(su.getAccessToken()).isNotBlank();
        assertThat(su.getRefreshToken()).isNotBlank();
    }

    @Test
    @Order(2)
    @DisplayName("密码错误：被拒（未到锁定阈值）")
    void login_wrongPassword_failed() {
        Map<String, Object> body = new HashMap<>();
        body.put("account", SUPER_ACCOUNT);
        body.put("password", "wrong12345");
        ResponseEntity<Map> resp = post("/admin/login", body, null);
        assertFailWithMsg(resp, "账号或密码错误");
    }

    @Test
    @Order(3)
    @DisplayName("未登录访问 admin 接口：401")
    void unauthenticated_accessDenied() {
        assertFail(get("/admin/list", null));
        assertFail(get("/admin/me", null));
    }

    @Test
    @Order(4)
    @DisplayName("用户 token 访问 admin 接口：被拒（身份隔离）")
    void userToken_accessAdmin_denied() {
        AuthUser normalUser = newAuthedUser(); // 普通用户
        assertFail(get("/admin/me", normalUser));
        assertFail(get("/admin/list", normalUser));
    }

    @Test
    @Order(5)
    @DisplayName("超管新增普通管理员：成功")
    void add_normalAdmin_success() {
        AuthUser su = superLogin();
        normalAccount = "139" + RandomUtil.randomNumbers(8);
        Map<String, Object> body = new HashMap<>();
        body.put("account", normalAccount);
        body.put("password", NORMAL_PASSWORD);
        body.put("name", "测试员");
        body.put("phone", normalAccount);
        body.put("status", AdminStatus.NORMAL.getCode());
        body.put("role", AdminRole.NORMAL.getCode());
        assertOk(post("/admin/add", body, su));
    }

    @Test
    @Order(6)
    @DisplayName("普通管理员登录：成功且可访问 me")
    void normalAdmin_login_success() {
        Map<String, Object> body = new HashMap<>();
        body.put("account", normalAccount);
        body.put("password", NORMAL_PASSWORD);
        ResponseEntity<Map> resp = post("/admin/login", body, null);
        assertOk(resp);
        AuthUser na = toAuthUser(resp);
        assertOk(get("/admin/me", na));
    }

    @Test
    @Order(7)
    @DisplayName("普通管理员调超管接口（list/add）：被拒（垂直越权）")
    void normalAdmin_superOnlyApi_denied() {
        Map<String, Object> body = new HashMap<>();
        body.put("account", normalAccount);
        body.put("password", NORMAL_PASSWORD);
        AuthUser na = toAuthUser(post("/admin/login", body, null));

        assertFailWithMsg(get("/admin/list", na), "无权");

        Map<String, Object> addBody = new HashMap<>();
        addBody.put("account", "139" + RandomUtil.randomNumbers(8));
        addBody.put("password", "xyz123456");
        assertFailWithMsg(post("/admin/add", addBody, na), "无权");
    }

    @Test
    @Order(8)
    @DisplayName("普通管理员连续 5 次密码错误：账号被锁定，正确密码也被拒")
    void login_failLock_after5Attempts() {
        for (int i = 0; i < NumConstants.ADMIN_LOGIN_FAIL_LIMIT; i++) {
            Map<String, Object> body = new HashMap<>();
            body.put("account", normalAccount);
            body.put("password", "wrong" + i + "12345");
            assertFail(post("/admin/login", body, null));
        }
        // 第 6 次即使密码正确，也被锁定拒绝
        Map<String, Object> body = new HashMap<>();
        body.put("account", normalAccount);
        body.put("password", NORMAL_PASSWORD);
        assertFailWithMsg(post("/admin/login", body, null), "锁定");
        // 清理：解除锁定，避免影响其他用例
        redis.delete(PrefixConstants.ADMIN_LOGIN_FAIL + normalAccount);
    }

    @Test
    @Order(9)
    @DisplayName("超管 /admin/me：返回本人信息且 role=SUPER")
    void me_returnsSelfWithRole() {
        AuthUser su = superLogin();
        ResponseEntity<Map> resp = get("/admin/me", su);
        assertOk(resp);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(String.valueOf(data.get("account"))).isEqualTo(SUPER_ACCOUNT);
        assertThat(Integer.valueOf(String.valueOf(data.get("role")))).isEqualTo(AdminRole.SUPER.getCode());
    }

    @Test
    @Order(10)
    @DisplayName("超管 /admin/list：分页返回全部管理员（含超管与普通）")
    void list_returnsAllAdmins() {
        AuthUser su = superLogin();
        ResponseEntity<Map> resp = get("/admin/list?pageNum=1&pageSize=10", su);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).isNotEmpty();
        // 用 account 精确筛选断言两者在册（不依赖第一页内容——历史用例每次新增管理员，
        // 累积后超管可能被挤出第一页，导致"第一页含超管"断言漂移）
        Map<String, Object> bySuper = (Map<String, Object>) get("/admin/list?account=" + SUPER_ACCOUNT, su)
                .getBody().get("data");
        assertThat((List<?>) bySuper.get("records")).anyMatch(r ->
                SUPER_ACCOUNT.equals(String.valueOf(((Map<?, ?>) r).get("account"))));
        Map<String, Object> byNormal = (Map<String, Object>) get("/admin/list?account=" + normalAccount, su)
                .getBody().get("data");
        assertThat((List<?>) byNormal.get("records")).anyMatch(r ->
                normalAccount.equals(String.valueOf(((Map<?, ?>) r).get("account"))));
        // total 为 long 类型，被 JacksonConfig 统一序列化为 String
        assertThat(Long.parseLong(String.valueOf(data.get("total")))).isGreaterThanOrEqualTo(2);
    }

    @Test
    @Order(11)
    @DisplayName("禁用用户后：旧 accessToken 立即失效（即使未过期/未进黑名单），解禁后恢复")
    void disableUser_oldAccessToken_immediatelyRejected() {
        // 1. 普通用户注册+登录（带 @NoAuthAnnotation 的 /user/me 登录即可访问）
        AuthUser user = newAuthedUser();
        assertOk(get("/user/me", user));

        // 2. 超管禁用该用户（status=DISABLED）
        AuthUser su = superLogin();
        assertOk(post("/admin/user/" + user.getUserId()
                + "/enable?status=" + UserStatus.DISABLED.getCode(), null, su));

        // 3. 旧 AT 未过期、未进黑名单，但必须立即被拒（JwtInterceptor 禁用标记兜底，
        //    修复"禁用只删 RT、AT 仍有效"的漏洞）
        assertFailWithMsg(get("/user/me", user), "禁用");

        // 4. 解禁后恢复访问（AT 未过期可继续使用）
        assertOk(post("/admin/user/" + user.getUserId()
                + "/enable?status=" + UserStatus.NORMAL.getCode(), null, su));
        assertOk(get("/user/me", user));
    }

    @Test
    @Order(12)
    @DisplayName("超管 /admin/user/{id}：返回完整详情；不存在的 id 返回用户不存在")
    void userDetail_byPathId() {
        AuthUser su = superLogin();
        AuthUser user = newAuthedUser();

        // 详情返回完整字段（含积分/余额等资产数据）
        ResponseEntity<Map> resp = get("/admin/user/" + user.getUserId(), su);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(String.valueOf(data.get("id"))).isEqualTo(String.valueOf(user.getUserId()));
        assertThat(data).containsKeys("username", "realName", "phone", "studentId", "avatar",
                "points", "creditScore", "balance", "status", "authStatus", "createTime", "updateTime");

        // 不存在的用户
        assertFailWithMsg(get("/admin/user/999999999999999999", su), "用户不存在");
    }

    @Test
    @Order(13)
    @DisplayName("超管 /admin/user/list：仅返回精简字段（不含资产数据）")
    void userList_onlyLeanFields() {
        AuthUser su = superLogin();
        newAuthedUser(); // 保证至少存在一个用户

        ResponseEntity<Map> resp = get("/admin/user/list?pageNum=1&pageSize=10", su);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).isNotEmpty();
        Map<String, Object> first = records.get(0);
        assertThat(first).containsKeys("id", "username", "realName", "phone", "studentId",
                "avatar", "status", "authStatus", "createTime");
        assertThat(first).doesNotContainKeys("points", "creditScore", "balance", "updateTime");
    }

    @Test
    @Order(14)
    @DisplayName("超管 /admin/{id}：新风格详情可访问；不存在的管理员被拒")
    void adminDetail_byPathId() {
        AuthUser su = superLogin();

        ResponseEntity<Map> resp = get("/admin/" + su.getUserId(), su);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(String.valueOf(data.get("account"))).isEqualTo(SUPER_ACCOUNT);

        assertFailWithMsg(get("/admin/999999999999999999", su), "管理员不存在");
    }

    @Test
    @Order(15)
    @DisplayName("超管 /admin/list：分页参数与角色筛选生效")
    void list_paginationAndFilter() {
        AuthUser su = superLogin();

        // pageSize=1：只回 1 条，total 仍为全量
        ResponseEntity<Map> resp = get("/admin/list?pageNum=1&pageSize=1", su);
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        assertThat(records).hasSize(1);

        // 角色筛选：role=0（枚举 code，项目全局枚举转换器只认 @EnumValue 值）只返回超级管理员
        ResponseEntity<Map> byRole = get("/admin/list?role=" + AdminRole.SUPER.getCode() + "&pageNum=1&pageSize=10", su);
        assertOk(byRole);
        List<Map<String, Object>> suRecords = (List<Map<String, Object>>)
                ((Map<String, Object>) byRole.getBody().get("data")).get("records");
        assertThat(suRecords).allMatch(r -> AdminRole.SUPER.getCode()
                .equals(Integer.valueOf(String.valueOf(r.get("role")))));

        // 账号精确筛选：命中超管且 total=1
        ResponseEntity<Map> byAccount = get("/admin/list?account=" + SUPER_ACCOUNT, su);
        assertOk(byAccount);
        Map<String, Object> accountData = (Map<String, Object>) byAccount.getBody().get("data");
        List<Map<String, Object>> accountRecords = (List<Map<String, Object>>) accountData.get("records");
        assertThat(accountRecords).hasSize(1);
        assertThat(Long.parseLong(String.valueOf(accountData.get("total")))).isEqualTo(1);
    }

    @Test
    @Order(16)
    @DisplayName("登出后旧 accessToken 立即失效（黑名单）")
    void logout_blacklistsAccessToken() {
        AuthUser su = superLogin();
        assertOk(post("/admin/logout", null, su));
        assertFail(get("/admin/me", su));
    }

    @Test
    @Order(17)
    @DisplayName("超管 PUT /admin/user/{id}：修改基本信息成功，重复 username/格式错误/全空被拒")
    void updateUser_basicInfo() {
        AuthUser su = superLogin();
        AuthUser userA = newAuthedUser();
        AuthUser userB = newAuthedUser();

        // 全空：至少提供一个字段
        assertFailWithMsg(put("/admin/user/" + userA.getUserId(), new HashMap<>(), su), "至少提供一个");

        // 正常修改 username/realName
        String newUsername = "u" + RandomUtil.randomNumbers(8);
        Map<String, Object> body = new HashMap<>();
        body.put("username", newUsername);
        body.put("realName", "新名字");
        assertOk(put("/admin/user/" + userA.getUserId(), body, su));

        // 详情能读到新值（同时验证缓存已清理）
        Map<String, Object> data = (Map<String, Object>) get("/admin/user/" + userA.getUserId(), su).getBody().get("data");
        assertThat(String.valueOf(data.get("username"))).isEqualTo(newUsername);
        assertThat(String.valueOf(data.get("realName"))).isEqualTo("新名字");

        // 用户名唯一性冲突：改成用户 B 的 username
        Map<String, Object> conflict = new HashMap<>();
        conflict.put("username", String.valueOf(
                ((Map<?, ?>) get("/admin/user/" + userB.getUserId(), su).getBody().get("data")).get("username")));
        assertFailWithMsg(put("/admin/user/" + userA.getUserId(), conflict, su), "用户名已被占用");

        // 手机号格式错误
        Map<String, Object> badPhone = new HashMap<>();
        badPhone.put("phone", "123");
        assertFailWithMsg(put("/admin/user/" + userA.getUserId(), badPhone, su), "手机号格式错误");
    }

    @Test
    @Order(18)
    @DisplayName("超管 POST /admin/user/{id}/points：加减分成功，扣成负数/缺 reason 被拒")
    void adjustPoints_boundsAndValid() {
        AuthUser su = superLogin();
        AuthUser user = newAuthedUser();

        Map<String, Object> before = (Map<String, Object>) get("/admin/user/" + user.getUserId(), su).getBody().get("data");
        int origin = Integer.parseInt(String.valueOf(before.get("points")));

        // 加 100 成功
        Map<String, Object> add = new HashMap<>();
        add.put("delta", 100);
        add.put("reason", "活动奖励");
        assertOk(post("/admin/user/" + user.getUserId() + "/points", add, su));

        Map<String, Object> after = (Map<String, Object>) get("/admin/user/" + user.getUserId(), su).getBody().get("data");
        assertThat(Integer.parseInt(String.valueOf(after.get("points")))).isEqualTo(origin + 100);

        // 调整通知已同步落库（与业务同事务，站内信必达）：用户侧通知列表可见，type=积分调整(13)，内容含 +100
        ResponseEntity<Map> noticeResp = get("/notification?pageNum=1&pageSize=1000", user);
        assertOk(noticeResp);
        List<Map<String, Object>> notices = (List<Map<String, Object>>)
                ((Map<String, Object>) noticeResp.getBody().get("data")).get("records");
        assertThat(notices).anyMatch(n ->
                NotificationType.POINTS_ADJUST.getType().equals(Integer.valueOf(String.valueOf(n.get("type"))))
                        && String.valueOf(n.get("content")).contains("+100"));

        // 扣成负数被拒
        Map<String, Object> overDraw = new HashMap<>();
        overDraw.put("delta", -20000);
        overDraw.put("reason", "违规扣分");
        assertFailWithMsg(post("/admin/user/" + user.getUserId() + "/points", overDraw, su), "积分不足以扣减");

        // reason 为空被拒（@Valid 校验）
        Map<String, Object> noReason = new HashMap<>();
        noReason.put("delta", 10);
        assertFail(post("/admin/user/" + user.getUserId() + "/points", noReason, su));
    }

    @Test
    @Order(19)
    @DisplayName("超管 POST /admin/user/{id}/credit：调整成功，超出 0-100 区间被拒")
    void adjustCredit_rangeBound() {
        AuthUser su = superLogin();
        AuthUser user = newAuthedUser();

        Map<String, Object> before = (Map<String, Object>) get("/admin/user/" + user.getUserId(), su).getBody().get("data");
        int origin = Integer.parseInt(String.valueOf(before.get("creditScore")));

        // 加 10 成功
        Map<String, Object> add = new HashMap<>();
        add.put("delta", 10);
        add.put("reason", "申诉恢复");
        assertOk(post("/admin/user/" + user.getUserId() + "/credit", add, su));

        Map<String, Object> after = (Map<String, Object>) get("/admin/user/" + user.getUserId(), su).getBody().get("data");
        assertThat(Integer.parseInt(String.valueOf(after.get("creditScore")))).isEqualTo(origin + 10);

        // 信誉分调整通知已同步落库：type=信誉分调整(14)，内容含 +10
        ResponseEntity<Map> creditNoticeResp = get("/notification?pageNum=1&pageSize=1000", user);
        assertOk(creditNoticeResp);
        List<Map<String, Object>> creditNotices = (List<Map<String, Object>>)
                ((Map<String, Object>) creditNoticeResp.getBody().get("data")).get("records");
        assertThat(creditNotices).anyMatch(n ->
                NotificationType.CREDIT_ADJUST.getType().equals(Integer.valueOf(String.valueOf(n.get("type"))))
                        && String.valueOf(n.get("content")).contains("+10"));

        // 超出上限被拒
        Map<String, Object> overflow = new HashMap<>();
        overflow.put("delta", 10000);
        overflow.put("reason", "测试溢出");
        assertFailWithMsg(post("/admin/user/" + user.getUserId() + "/credit", overflow, su), "0-100");

        // 扣成负数被拒
        Map<String, Object> negative = new HashMap<>();
        negative.put("delta", -10000);
        negative.put("reason", "测试负数");
        assertFailWithMsg(post("/admin/user/" + user.getUserId() + "/credit", negative, su), "0-100");
    }

    @Test
    @Order(20)
    @DisplayName("超管 POST /admin/user/{id}/kick：RT 全部删除（全设备下线），账号状态不变")
    void kick_deletesRefreshTokens() {
        AuthUser su = superLogin();
        AuthUser user = newAuthedUser();

        // 登录后 RT 已写入 Redis（user_tokens:{id} Hash，Redisson RMapCache 写入）
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + user.getUserId())
                .containsKey(user.getDeviceId())).isTrue();

        assertOk(post("/admin/user/" + user.getUserId() + "/kick", null, su));

        // RT 已删除（所有设备立即无法续期）
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + user.getUserId())
                .containsKey(user.getDeviceId())).isFalse();

        // 与禁用不同：kick 不改账号状态
        Map<String, Object> data = (Map<String, Object>) get("/admin/user/" + user.getUserId(), su).getBody().get("data");
        assertThat(Integer.valueOf(String.valueOf(data.get("status")))).isEqualTo(UserStatus.NORMAL.getCode());
    }
}
