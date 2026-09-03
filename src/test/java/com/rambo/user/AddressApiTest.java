package com.rambo.user;

import cn.hutool.core.util.RandomUtil;
import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFail;
import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 地址模块测试：新增 / 上限 3 / 默认地址唯一互斥 / 列表隔离 / 更新 / 删除 / 越权防护 / 未登录。
 *
 * 地址接口为 @NoAuthAnnotation（登录即可访问，不要求学生认证），用 newUser() 即可。
 */
class AddressApiTest extends BaseApiTest {

    /** 构造完整地址请求体 */
    private Map<String, Object> addressBody(String receiverName, int isDefault) {
        Map<String, Object> body = new HashMap<>();
        body.put("receiverName", receiverName);
        body.put("receiverPhone", "138" + RandomUtil.randomNumbers(8));
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detailAddress", "科技园路 " + RandomUtil.randomNumbers(3) + " 号");
        body.put("isDefault", isDefault);
        return body;
    }

    /** 当前用户地址列表 */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> addressList(AuthUser user) {
        ResponseEntity<Map> resp = get("/address/list", user);
        assertOk(resp);
        return (List<Map<String, Object>>) resp.getBody().get("data");
    }

    /** 保存一条地址，返回地址 id */
    private Long saveAddress(AuthUser user, int isDefault) {
        ResponseEntity<Map> resp = post("/address", addressBody("收货人" + RandomUtil.randomNumbers(3), isDefault), user);
        assertOk(resp);
        // 列表第一条（新地址默认排前）的 id 即刚插入的地址
        List<Map<String, Object>> list = addressList(user);
        assertThat(list).isNotEmpty();
        return Long.parseLong(String.valueOf(list.get(0).get("id")));
    }

    // ==================== 新增 ====================

    @Test
    @DisplayName("新增地址：完整字段保存成功并出现在列表")
    void saveAddress_success() {
        AuthUser user = newUser();
        String name = "张三" + RandomUtil.randomNumbers(2);
        assertOk(post("/address", addressBody(name, 0), user));

        List<Map<String, Object>> list = addressList(user);
        assertThat(list).anyMatch(a -> name.equals(String.valueOf(a.get("receiverName"))));
    }

    @Test
    @DisplayName("新增地址：必填字段缺失被拒（@Valid）")
    void saveAddress_missingField_failed() {
        AuthUser user = newUser();
        Map<String, Object> body = addressBody("缺省地址", 0);
        body.remove("receiverName");
        assertFailWithMsg(post("/address", body, user), "收货人姓名");
    }

    @Test
    @DisplayName("新增地址：最多 3 个，第 4 个被拒")
    void saveAddress_overLimit_failed() {
        AuthUser user = newUser();
        saveAddress(user, 0);
        saveAddress(user, 0);
        saveAddress(user, 0);

        assertFailWithMsg(post("/address", addressBody("超额地址", 0), user), "3个");
        // 列表仍为 3 条
        assertThat(addressList(user)).hasSize(3);
    }

    @Test
    @DisplayName("默认地址唯一：新增默认地址后旧默认地址自动取消")
    void saveDefaultAddress_switchesPreviousDefault() {
        AuthUser user = newUser();
        saveAddress(user, 1); // 第 1 个默认
        saveAddress(user, 0);
        saveAddress(user, 1); // 第 2 个默认 → 旧默认应被取消

        List<Map<String, Object>> list = addressList(user);
        assertThat(list).hasSize(3);
        // 仅 1 条默认
        long defaultCount = list.stream()
                .filter(a -> Integer.valueOf(String.valueOf(a.get("isDefault"))).equals(1))
                .count();
        assertThat(defaultCount).isEqualTo(1);
        // 列表按 isDefault 降序，第一条是默认
        assertThat(Integer.valueOf(String.valueOf(list.get(0).get("isDefault")))).isEqualTo(1);
    }

    // ==================== 列表 ====================

    @Test
    @DisplayName("列表隔离：A 的地址不出现在 B 的列表中")
    void list_onlyOwnAddresses() {
        AuthUser userA = newUser();
        saveAddress(userA, 0);

        AuthUser userB = newUser();
        assertThat(addressList(userB)).isEmpty();
    }

    // ==================== 更新 ====================

    @Test
    @DisplayName("更新地址：修改收货人姓名成功，列表可见新值")
    void updateAddress_success() {
        AuthUser user = newUser();
        Long id = saveAddress(user, 0);

        Map<String, Object> body = addressBody("新收货人", 1);
        assertOk(put("/address/" + id, body, user));

        List<Map<String, Object>> list = addressList(user);
        Map<String, Object> updated = list.stream()
                .filter(a -> String.valueOf(a.get("id")).equals(String.valueOf(id)))
                .findFirst().orElseThrow();
        assertThat(String.valueOf(updated.get("receiverName"))).isEqualTo("新收货人");
        assertThat(Integer.valueOf(String.valueOf(updated.get("isDefault")))).isEqualTo(1);
    }

    @Test
    @DisplayName("更新地址：他人地址被拒（越权防护）")
    void updateAddress_others_denied() {
        AuthUser userA = newUser();
        Long id = saveAddress(userA, 0);

        AuthUser userB = newUser();
        assertFailWithMsg(put("/address/" + id, addressBody("越权", 0), userB), "无权");
    }

    @Test
    @DisplayName("更新地址：地址不存在被拒")
    void updateAddress_notExist_failed() {
        AuthUser user = newUser();
        assertFailWithMsg(put("/address/999999999999999999", addressBody("不存在", 0), user), "地址不存在");
    }

    // ==================== 删除 ====================

    @Test
    @DisplayName("删除地址：删除成功，列表不再包含")
    void deleteAddress_success() {
        AuthUser user = newUser();
        Long id = saveAddress(user, 0);

        assertOk(delete("/address/" + id, user));
        assertThat(addressList(user)).isEmpty();
    }

    @Test
    @DisplayName("删除地址：他人地址被拒（越权防护）")
    void deleteAddress_others_denied() {
        AuthUser userA = newUser();
        Long id = saveAddress(userA, 0);

        AuthUser userB = newUser();
        assertFailWithMsg(delete("/address/" + id, userB), "无权");
        // 他人删除失败不影响原地址
        assertThat(addressList(userA)).hasSize(1);
    }

    // ==================== 登录要求 ====================

    @Test
    @DisplayName("地址接口：未登录被拒")
    void address_withoutLogin_failed() {
        assertFail(post("/address", addressBody("匿名", 0), null));
        assertFail(get("/address/list", null));
    }
}
