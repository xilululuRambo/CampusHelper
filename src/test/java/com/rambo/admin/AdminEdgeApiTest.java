package com.rambo.admin;

import cn.hutool.core.util.RandomUtil;
import com.rambo.BaseApiTest;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.enumType.CategoryStatus;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.module.task.enums.TaskStatus;
import com.rambo.infrastructure.auth.JwtUtil;
import com.rambo.helper.AuthUser;
import com.rambo.module.admin.enums.AdminRole;
import com.rambo.module.admin.enums.AdminStatus;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsService;
import com.rambo.module.task.server.service.TaskService;
import com.rambo.module.user.enums.UserStatus;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 管理员补充测试：
 *  1) 运营模块权限模型固化（普通管理员可运营，管理模块仅超管——由 AdminApiTest 覆盖）
 *  2) 超管重置密码闭环（新密码可登录 / 旧密码失效 / RT 清除）
 *  3) 禁用管理员后旧 accessToken 立即失效（与用户侧 USER_DISABLED 语义对齐）
 *  4) 商品强制下架联动取消未完成订单并退款
 *  5) 管理员下架任务（状态机 + 终态防护）
 *  6) 任务分类 CRUD 边界（重名拒绝 / 占用拒绝删除）
 * 种子超管：account=13900000001 password=admin123456
 */
class AdminEdgeApiTest extends BaseApiTest {

    private static final String SUPER_ACCOUNT = "13900000001";
    private static final String SUPER_PASSWORD = "admin123456";
    private static final String NORMAL_PASSWORD = "abc123456";
    private static final long PRICE = 1000L;

    @Resource
    private JwtUtil jwtUtil;
    @Resource
    private RedissonClient redissonClient;
    @Resource
    private UserService userService;
    @Resource
    private GoodsService goodsService;
    @Resource
    private GoodsOrderService goodsOrderService;
    @Resource
    private TaskService taskService;

    // ==================== 辅助方法 ====================

    /** 超管登录 */
    private AuthUser superLogin() {
        return loginAs(SUPER_ACCOUNT, SUPER_PASSWORD);
    }

    private AuthUser loginAs(String account, String password) {
        Map<String, Object> body = new HashMap<>();
        body.put("account", account);
        body.put("password", password);
        ResponseEntity<Map> resp = post("/admin/login", body, null);
        assertOk(resp);
        return toAuthUser(resp);
    }

    /** 从登录响应构造 AuthUser（id 从 token 解析，deviceId 固定占位） */
    private AuthUser toAuthUser(ResponseEntity<Map> resp) {
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        String at = (String) data.get("accessToken");
        String rt = (String) data.get("refreshToken");
        Long id = Long.parseLong(jwtUtil.parseStringClaim(at));
        return new AuthUser(String.valueOf(id), id, at, rt, "admin");
    }

    /** 超管新增一个普通管理员，返回其账号 */
    private String newNormalAdminAccount() {
        AuthUser su = superLogin();
        String account = "139" + RandomUtil.randomNumbers(8);
        Map<String, Object> body = new HashMap<>();
        body.put("account", account);
        body.put("password", NORMAL_PASSWORD);
        body.put("name", "测试员");
        body.put("phone", account);
        body.put("status", AdminStatus.NORMAL.getCode());
        body.put("role", AdminRole.NORMAL.getCode());
        assertOk(post("/admin/add", body, su));
        return account;
    }

    private Long publishGoods(AuthUser seller) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "管理员下架测试-" + System.currentTimeMillis());
        form.put("description", "测试商品");
        form.put("categoryId", "1");
        form.put("price", String.valueOf(PRICE));
        form.put("images", new ByteArrayResource("img".getBytes()) {
            @Override
            public String getFilename() {
                return "goods.jpg";
            }
        });
        assertOk(postMultipart("/goods", form, seller));
        ResponseEntity<Map> my = get("/goods/my?pageNum=1&pageSize=1", seller);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) my.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Long myLatestOrderId(AuthUser user) {
        ResponseEntity<Map> resp = get("/goods/order/my?pageNum=1&pageSize=1", user);
        assertOk(resp);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    /** 发布一个待接单任务并返回任务 id（复用既有发布链路：地址 → 任务） */
    private Long publishTask(AuthUser user) {
        return publishTask(user, 1);
    }

    private Long publishTask(AuthUser user, Integer categoryId) {
        Map<String, Object> address = new HashMap<>();
        address.put("receiverName", "测试");
        address.put("receiverPhone", user.getPhone());
        address.put("province", "广东省");
        address.put("city", "深圳市");
        address.put("district", "南山区");
        address.put("detailAddress", "测试路 1 号");
        address.put("isDefault", 0);
        assertOk(post("/address", address, user));
        ResponseEntity<Map> list = get("/address/list", user);
        List<Map<String, Object>> addrs = (List<Map<String, Object>>) list.getBody().get("data");
        Long addressId = Long.parseLong(String.valueOf(addrs.get(0).get("id")));

        Map<String, Object> body = new HashMap<>();
        body.put("title", "管理下架任务-" + System.currentTimeMillis());
        body.put("description", "测试描述");
        body.put("reward", 5);
        body.put("categoryId", categoryId);
        body.put("addressId", addressId);
        body.put("deadline", "2026-12-31 18:00:00");
        assertOk(postWithSubmitToken("/task", body, user, "publishTask"));

        ResponseEntity<Map> resp = get("/task/my-published?pageNum=1&pageSize=1", user);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        List<Map<String, Object>> records = (List<Map<String, Object>>) data.get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    // ==================== 用例 ====================

    @Test
    @DisplayName("权限模型固化：普通管理员可访问并操作运营模块（任务/商品/用户）")
    void normalAdmin_canOperateOpsModule() {
        String account = newNormalAdminAccount();
        AuthUser na = loginAs(account, NORMAL_PASSWORD);

        // 运营只读端点可用
        assertOk(get("/admin/task/list?pageNum=1&pageSize=10", na));
        assertOk(get("/admin/goods/list?pageNum=1&pageSize=10", na));
        assertOk(get("/admin/user/list?pageNum=1&pageSize=10", na));
        // 用户详情端点：管理员自身不在 t_user 表，须以真实用户 ID 验证
        AuthUser target = newAuthedUser();
        assertOk(get("/admin/user/" + target.getUserId(), na));

        // 运营写操作可用：踢下线一个新注册用户（不改变账号状态）
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + target.getUserId())
                .containsKey(target.getDeviceId())).isTrue();
        assertOk(post("/admin/user/" + target.getUserId() + "/kick", null, na));
        assertThat(redissonClient.getMapCache(PrefixConstants.USER_TOKENS + target.getUserId())
                .containsKey(target.getDeviceId())).isFalse();
        Map<String, Object> data = (Map<String, Object>) get("/admin/user/" + target.getUserId(), na)
                .getBody().get("data");
        assertThat(Integer.valueOf(String.valueOf(data.get("status"))))
                .isEqualTo(UserStatus.NORMAL.getCode());
    }

    @Test
    @DisplayName("超管重置密码：新密码可登录、旧密码失效、目标 RT 清除；普通管理员调重置被拒")
    void resetPassword_closedLoop() {
        String account = newNormalAdminAccount();
        AuthUser na = loginAs(account, NORMAL_PASSWORD);

        // 普通管理员调超管专属重置接口 → 无权
        assertFailWithMsg(post("/admin/" + na.getUserId()
                + "/resetPassword?newPassword=zzz123456", null, na), "无权");

        // 超管重置密码
        AuthUser su = superLogin();
        assertOk(post("/admin/" + na.getUserId()
                + "/resetPassword?newPassword=zzz123456", null, su));

        // 目标管理员 RT 已被删除（无法续期）
        assertThat(redissonClient.getBucket(PrefixConstants.ADMIN_TOKENS + na.getUserId())
                .isExists()).isFalse();

        // 新密码登录成功
        AuthUser renewed = loginAs(account, "zzz123456");
        assertOk(get("/admin/me", renewed));

        // 旧密码被拒（账号不存在/密码错误统一报错，防撞库）
        Map<String, Object> oldLogin = new HashMap<>();
        oldLogin.put("account", account);
        oldLogin.put("password", NORMAL_PASSWORD);
        assertFailWithMsg(post("/admin/login", oldLogin, null), "账号或密码错误");
    }

    @Test
    @DisplayName("禁用管理员：旧 accessToken 立即失效（即使未过期），解禁后恢复")
    void disableAdmin_oldToken_immediatelyRejected() {
        String account = newNormalAdminAccount();
        AuthUser na = loginAs(account, NORMAL_PASSWORD);
        assertOk(get("/admin/me", na));

        // 超管禁用该管理员
        AuthUser su = superLogin();
        Map<String, Object> body = new HashMap<>();
        body.put("name", "测试员");
        body.put("phone", account);
        body.put("status", AdminStatus.DISABLED.getCode());
        body.put("role", AdminRole.NORMAL.getCode());
        assertOk(post("/admin/" + na.getUserId() + "/update", body, su));

        // 旧 AT 未过期、未进黑名单，但必须立即被拒
        assertFailWithMsg(get("/admin/me", na), "禁用");

        // 禁用后无法登录（登录查询按状态过滤）
        Map<String, Object> login = new HashMap<>();
        login.put("account", account);
        login.put("password", NORMAL_PASSWORD);
        assertFailWithMsg(post("/admin/login", login, null), "账号或密码错误");

        // 解禁后恢复（登录成功后失败计数自动清零）
        body.put("status", AdminStatus.NORMAL.getCode());
        assertOk(post("/admin/" + na.getUserId() + "/update", body, su));
        AuthUser restored = loginAs(account, NORMAL_PASSWORD);
        assertOk(get("/admin/me", restored));
    }

    @Test
    @DisplayName("商品强制下架：商品置为下架，已付款未完成订单被取消并退款")
    void goodsOffShelf_cancelsActiveOrders() {
        AuthUser seller = newAuthedUser();
        AuthUser buyer = newAuthedUser();
        // 买家充值并购买付款 → 待发货
        userService.lambdaUpdate()
                .eq(User::getId, buyer.getUserId())
                .set(User::getBalance, PRICE * 2)
                .update();
        Long gId = publishGoods(seller);
        assertOk(post("/goods/buy/" + gId, null, buyer));
        Long oId = myLatestOrderId(buyer);
        assertOk(post("/goods/order/pay/" + oId, null, buyer));
        assertThat(goodsOrderService.getById(oId).getOrderStatus())
                .isEqualTo(GoodsOrderStatus.PENDING_SHIP);

        // 超管强制下架（GoodsStatus.DISABLED 的 @EnumValue 数值=2）
        AuthUser su = superLogin();
        assertOk(post("/admin/goods/status/" + gId
                + "?goodsStatus=" + GoodsStatus.DISABLED.getCode(), null, su));

        // 商品 → 下架
        assertThat(goodsService.getById(gId).getStatus()).isEqualTo(GoodsStatus.DISABLED);
        // 订单 → 已取消
        assertThat(goodsOrderService.getById(oId).getOrderStatus())
                .isEqualTo(GoodsOrderStatus.CANCELLED);
        // 买家余额已退回（退款闭环）
        assertThat(userService.getById(buyer.getUserId()).getBalance()).isEqualTo(PRICE * 2);
    }

    @Test
    @DisplayName("管理员下架任务：任务置为已取消，终态不可重复下架，不存在被拒")
    void taskDown_cancelsTask() {
        AuthUser publisher = newAuthedUser();
        Long taskId = publishTask(publisher);
        assertThat(taskService.getById(taskId).getStatus()).isEqualTo(TaskStatus.PENDING);

        AuthUser su = superLogin();
        assertOk(post("/admin/task/down/" + taskId, null, su));
        assertThat(taskService.getById(taskId).getStatus()).isEqualTo(TaskStatus.CANCELLED);

        // 终态（已取消）不可重复下架
        assertFailWithMsg(post("/admin/task/down/" + taskId, null, su), "状态");
        // 不存在的任务
        assertFailWithMsg(post("/admin/task/down/999999999999999999", null, su), "任务不存在");
    }

    @Test
    @DisplayName("任务分类：新增成功、重名被拒、占用删除被拒、空分类可删")
    void category_addDuplicateAndDeleteGuard() {
        AuthUser su = superLogin();
        String name = "管理分类" + RandomUtil.randomNumbers(4);

        Map<String, Object> add = new HashMap<>();
        add.put("name", name);
        add.put("description", "测试分类");
        add.put("status", CategoryStatus.NORMAL.getCode());
        assertOk(post("/admin/task/category/add", add, su));

        // 重名被拒
        assertFailWithMsg(post("/admin/task/category/add", add, su), "已存在");

        // 从公开分类列表取新分类 id（@NoAuthAnnotation 仅需登录，用超管身份访问）
        Long cid = findCategoryId(su, name);

        // 分类下有任务 → 拒绝删除（占用防护）
        AuthUser publisher = newAuthedUser();
        publishTask(publisher, cid.intValue());
        assertFailWithMsg(post("/admin/task/category/delete/" + cid, null, su), "存在任务");

        // 新建空分类 → 可删除
        String emptyName = "空分类" + RandomUtil.randomNumbers(4);
        Map<String, Object> addEmpty = new HashMap<>();
        addEmpty.put("name", emptyName);
        addEmpty.put("description", "空分类");
        addEmpty.put("status", CategoryStatus.NORMAL.getCode());
        assertOk(post("/admin/task/category/add", addEmpty, su));
        Long emptyId = findCategoryId(su, emptyName);
        assertOk(post("/admin/task/category/delete/" + emptyId, null, su));

        // 删除后列表不再包含
        ResponseEntity<Map> after = get("/task/category", su);
        List<Map<String, Object>> afterList = (List<Map<String, Object>>) after.getBody().get("data");
        assertThat(afterList).noneMatch(c -> emptyName.equals(String.valueOf(c.get("name"))));
    }

    private Long findCategoryId(AuthUser viewer, String name) {
        ResponseEntity<Map> list = get("/task/category", viewer);
        List<Map<String, Object>> categories = (List<Map<String, Object>>) list.getBody().get("data");
        return categories.stream()
                .filter(c -> name.equals(String.valueOf(c.get("name"))))
                .map(c -> Long.parseLong(String.valueOf(c.get("id"))))
                .findFirst()
                .orElseThrow(() -> new AssertionError("分类未出现在列表中: " + name));
    }
}
