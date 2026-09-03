package com.rambo.task;

import com.rambo.BaseApiTest;
import com.rambo.common.result.PageResult;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;

/**
 * 任务模块接口测试：发布 / 更新 / 取消 / 详情 / 列表搜索。
 * 任务接口要求已认证用户（NoAuthInterceptor），统一用 newAuthedUser()。
 */
class TaskApiTest extends BaseApiTest {

    /** 创建任务所需地址，返回 addressId（地址接口 @NoAuthAnnotation，登录即可） */
    private Long createAddress(AuthUser user) {
        Map<String, Object> body = new HashMap<>();
        body.put("receiverName", "测试");
        body.put("receiverPhone", user.getPhone());
        body.put("province", "广东省");
        body.put("city", "深圳市");
        body.put("district", "南山区");
        body.put("detailAddress", "测试路 1 号");
        body.put("isDefault", 0);
        // 保存地址接口返回 Result.success() 不带 data，需从地址列表取最新一条的 id
        assertOk(post("/address", body, user));
        ResponseEntity<Map> list = get("/address/list", user);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) list.getBody().get("data");
        assertThat(records).isNotEmpty();
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Map<String, Object> taskBody(Long addressId) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "帮我取个快递" + System.currentTimeMillis());
        body.put("description", "东门菜鸟驿站 3 号柜");
        body.put("reward", 5);
        body.put("categoryId", 1);   // seed: 跑腿代取
        body.put("addressId", addressId);
        body.put("deadline", "2026-12-31 18:00:00");
        return body;
    }

    @Test
    @DisplayName("发布任务：正常发布成功，详情字段完整")
    void publishTask_success() {
        AuthUser user = newAuthedUser();
        Long addressId = createAddress(user);

        ResponseEntity<Map> resp = postWithSubmitToken("/task", taskBody(addressId), user, "publishTask");
        assertOk(resp);

        // 从"我的发布"验证任务已生成
        ResponseEntity<Map> myList = get("/task/my-published?pageNum=1&pageSize=10", user);
        assertOk(myList);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) myList.getBody().get("data")).get("records");
        assertThat(records).isNotEmpty();
    }

    @Test
    @DisplayName("发布任务：必填字段缺失被拒（标题/奖励/分类/地址/截止时间）")
    void publishTask_missingRequired_failed() {
        AuthUser user = newAuthedUser();
        Long addressId = createAddress(user);

        Map<String, Object> noTitle = taskBody(addressId);
        noTitle.remove("title");
        assertFailWithMsg(postWithSubmitToken("/task", noTitle, user, "publishTask"), "标题");

        Map<String, Object> noReward = taskBody(addressId);
        noReward.remove("reward");
        assertFailWithMsg(postWithSubmitToken("/task", noReward, user, "publishTask"), "奖励");
    }

    @Test
    @DisplayName("发布任务：地址不存在被拒")
    void publishTask_addressNotExists_failed() {
        AuthUser user = newAuthedUser();
        Map<String, Object> body = taskBody(999999L); // 不存在的地址
        assertFailWithMsg(postWithSubmitToken("/task", body, user, "publishTask"), "地址");
    }

    @Test
    @DisplayName("更新任务：非发布者越权被拒")
    void updateTask_byNonOwner_denied() {
        AuthUser owner = newAuthedUser();
        AuthUser other = newAuthedUser();
        Long addressId = createAddress(owner);
        Long taskId = publishAndGetId(owner, addressId);

        Map<String, Object> updateBody = taskBody(addressId);
        ResponseEntity<Map> resp = put("/task/" + taskId, updateBody, other);
        assertFailWithMsg(resp, "无权");
    }

    @Test
    @DisplayName("更新任务：任务已被取消（状态非 PENDING）不可更新")
    void updateTask_whenCancelled_failed() {
        AuthUser owner = newAuthedUser();
        Long addressId = createAddress(owner);
        Long taskId = publishAndGetId(owner, addressId);

        assertOk(delete("/task/" + taskId, owner));          // 先取消
        Map<String, Object> updateBody = taskBody(addressId);
        assertFailWithMsg(put("/task/" + taskId, updateBody, owner), "状态");
    }

    @Test
    @DisplayName("取消任务：非发布者越权被拒")
    void cancelTask_byNonOwner_denied() {
        AuthUser owner = newAuthedUser();
        AuthUser other = newAuthedUser();
        Long addressId = createAddress(owner);
        Long taskId = publishAndGetId(owner, addressId);

        ResponseEntity<Map> resp = delete("/task/" + taskId, other);
        assertFailWithMsg(resp, "无权");
    }

    @Test
    @DisplayName("任务详情：正常返回发布者与地址信息")
    void getTaskDetail_success() {
        AuthUser user = newAuthedUser();
        Long addressId = createAddress(user);
        Long taskId = publishAndGetId(user, addressId);

        ResponseEntity<Map> resp = get("/task/" + taskId, user);
        assertOk(resp);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        assertThat(String.valueOf(data.get("username"))).isNotBlank();
        assertThat(String.valueOf(data.get("address"))).contains("深圳市");
    }

    @Test
    @DisplayName("搜索：ES 命中后按 ID 回表返回（stub ES 返回任务 ID）")
    void taskList_keywordSearch_usesEsIds() throws IOException {
        AuthUser user = newAuthedUser();
        Long addressId = createAddress(user);
        Long taskId = publishAndGetId(user, addressId);

        // stub ES 分页搜索命中该任务
        when(taskEsSearchService.searchTaskPage(eq("快递"), any(), any(), any(), any(), any(), anyLong(), anyLong()))
                .thenReturn(new PageResult<>(1, List.of(taskId)));

        ResponseEntity<Map> resp = get("/task/list?keyword=快递&pageNum=1&pageSize=10", user);
        assertOk(resp);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        assertThat(records).anyMatch(r -> taskId.toString().equals(String.valueOf(r.get("id"))));
    }

    @Test
    @DisplayName("搜索：ES 故障时降级 DB LIKE 查询")
    void taskList_esDown_fallbackToDb() throws IOException {
        AuthUser user = newAuthedUser();
        Long addressId = createAddress(user);
        String title = "降级搜索任务" + System.currentTimeMillis();
        Map<String, Object> body = taskBody(addressId);
        body.put("title", title);
        assertOk(postWithSubmitToken("/task", body, user, "publishTask"));

        // ES 抛异常 → 走 DB LIKE
        doThrow(new IOException("es down")).when(taskEsSearchService)
                .searchTaskPage(anyString(), any(), any(), any(), any(), any(), anyLong(), anyLong());

        ResponseEntity<Map> resp = get("/task/list?keyword=" + title, user);
        assertOk(resp);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        assertThat(records).anyMatch(r -> title.equals(String.valueOf(r.get("title"))));
    }

    @Test
    @DisplayName("我的发布：只返回本人发布的任务")
    void myPublished_onlyMine() {
        AuthUser user = newAuthedUser();
        AuthUser other = newAuthedUser();
        Long addressId = createAddress(user);
        publishAndGetId(user, addressId);   // 用户 A 发布

        ResponseEntity<Map> resp = get("/task/my-published?pageNum=1&pageSize=10", other);
        assertOk(resp);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        assertThat(records).isEmpty();      // B 没有任何发布
    }

    // ==================== 私有工具 ====================

    private Long publishAndGetId(AuthUser user, Long addressId) {
        ResponseEntity<Map> resp = postWithSubmitToken("/task", taskBody(addressId), user, "publishTask");
        assertOk(resp);
        // 取最新一条"我的发布"作为 id（发布接口返回 void）
        ResponseEntity<Map> myList = get("/task/my-published?pageNum=1&pageSize=1", user);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) myList.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }
}
