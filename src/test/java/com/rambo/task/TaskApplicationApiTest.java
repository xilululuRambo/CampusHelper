package com.rambo.task;

import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import com.rambo.module.task.pojo.entity.TaskOrder;
import com.rambo.module.task.server.service.TaskOrderService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;

/**
 * 任务申请全链路状态机测试（串行编排）：
 *   发布 → 申请 → 同意 → 提交完成证据 → 发布者确认完成 → 生成任务订单 → 双方互评
 * 一条完整业务链走通，任何一环状态机实现出错都会让用例变红。
 *
 * 另含独立的鉴权/状态机非法流转用例。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class TaskApplicationApiTest extends BaseApiTest {

    @Resource
    private TaskOrderService taskOrderService;

    // 串行链路共享状态
    private static AuthUser publisher;
    private static AuthUser applicant;
    private static Long taskId;
    private static Long applicationId;
    private static Long taskOrderId;

    // ==================== 全链路（@Order 串行） ====================

    @Test
    @Order(1)
    @DisplayName("链路-1：准备发布者/申请者，发布任务")
    void step1_publishTask() {
        publisher = newAuthedUser();
        applicant = newAuthedUser();
        Long addressId = createAddress(publisher);

        Map<String, Object> body = taskBody(addressId);
        body.put("title", "全链路任务-" + System.currentTimeMillis());
        assertOk(postWithSubmitToken("/task", body, publisher, "publishTask"));

        taskId = myLatestTaskId(publisher);
        assertOk(get("/task/" + taskId, publisher));
    }

    @Test
    @Order(2)
    @DisplayName("链路-2：申请者申请任务，重复申请被拒")
    void step2_applyTask() {
        ResponseEntity<Map> resp = postWithSubmitToken("/task/application/" + taskId + "?reason=我很擅长", null, applicant, "applyForTask");
        assertOk(resp);
        applicationId = myLatestApplicationId(applicant, taskId);

        // 重复申请 → 唯一索引防重
        ResponseEntity<Map> dup = postWithSubmitToken("/task/application/" + taskId + "?reason=再次申请", null, applicant, "applyForTask");
        assertFailWithMsg(dup, "重复申请");
    }

    @Test
    @Order(3)
    @DisplayName("链路-3：发布者同意申请，任务转进行中")
    void step3_acceptApplication() {
        ResponseEntity<Map> resp = put("/task/application/" + applicationId + "/accept", null, publisher);
        assertOk(resp);

        // 任务状态应为进行中（1）
        Map<?, ?> data = (Map<?, ?>) get("/task/" + taskId, publisher).getBody().get("data");
        org.assertj.core.api.Assertions.assertThat(String.valueOf(data.get("status"))).isEqualTo("1");
    }

    @Test
    @Order(4)
    @DisplayName("链路-4：申请者提交完成证据（multipart，OSS mock）")
    void step4_completeTask() {
        Map<String, Object> form = new HashMap<>();
        form.put("completeEvidence", new ByteArrayResource("evidence-bytes".getBytes()) {
            @Override
            public String getFilename() {
                return "evidence.jpg";
            }
        });
        ResponseEntity<Map> resp = putMultipart("/task/application/" + applicationId + "/confirm-complete", form, applicant);
        assertOk(resp);
    }

    @Test
    @Order(5)
    @DisplayName("链路-5：发布者确认完成，生成任务订单")
    void step5_confirmComplete() {
        ResponseEntity<Map> resp = put("/task/" + taskId + "/confirm-complete", null, publisher);
        assertOk(resp);

        // 订单已生成（走 Service 查库，规避 GET+@RequestBody 缺陷接口）
        TaskOrder order = taskOrderService.lambdaQuery()
                .eq(TaskOrder::getTaskId, taskId).one();
        org.assertj.core.api.Assertions.assertThat(order).isNotNull();
        taskOrderId = order.getId();
    }

    @Test
    @Order(6)
    @DisplayName("链路-6：任务完成后双方互评")
    void step6_evaluate() {
        // 申请者评价发布者
        Map<String, Object> e1 = new HashMap<>();
        e1.put("orderId", taskOrderId);
        e1.put("score", 5);
        e1.put("content", "任务完成得很好");
        assertOk(post("/task/evaluation", e1, applicant));

        // 发布者评价申请者
        Map<String, Object> e2 = new HashMap<>();
        e2.put("orderId", taskOrderId);
        e2.put("score", 5);
        e2.put("content", "很靠谱的接单者");
        assertOk(post("/task/evaluation", e2, publisher));

        // 重复评价被拒
        assertFailWithMsg(post("/task/evaluation", e1, applicant), "已评价");
    }

    // ==================== 独立鉴权 / 状态机用例 ====================

    @Test
    @Order(7)
    @DisplayName("申请自己的任务被拒")
    void apply_ownTask_denied() {
        AuthUser user = newAuthedUser();
        Long addressId = createAddress(user);
        Map<String, Object> body = taskBody(addressId);
        body.put("title", "自己的任务-" + System.currentTimeMillis());
        assertOk(postWithSubmitToken("/task", body, user, "publishTask"));
        Long myTaskId = myLatestTaskId(user);

        ResponseEntity<Map> resp = postWithSubmitToken("/task/application/" + myTaskId + "?reason=我想接", null, user, "applyForTask");
        assertFailWithMsg(resp, "自己发布");
    }

    @Test
    @Order(8)
    @DisplayName("同意申请：非发布者越权被拒")
    void accept_byNonPublisher_denied() {
        AuthUser owner = newAuthedUser();
        AuthUser stranger = newAuthedUser();
        AuthUser applicantUser = newAuthedUser();
        Long addressId = createAddress(owner);
        Map<String, Object> body = taskBody(addressId);
        body.put("title", "越权测试任务-" + System.currentTimeMillis());
        assertOk(postWithSubmitToken("/task", body, owner, "publishTask"));
        Long tId = myLatestTaskId(owner);
        assertOk(postWithSubmitToken("/task/application/" + tId + "?reason=申请", null, applicantUser, "applyForTask"));
        Long appId = myLatestApplicationId(applicantUser, tId);

        ResponseEntity<Map> resp = put("/task/application/" + appId + "/accept", null, stranger);
        assertFailWithMsg(resp, "无权");
    }

    // ==================== 私有工具 ====================

    private Long createAddress(AuthUser user) {
        Map<String, Object> address = new HashMap<>();
        address.put("receiverName", "测试");
        address.put("receiverPhone", user.getPhone());
        address.put("province", "广东省");
        address.put("city", "深圳市");
        address.put("district", "南山区");
        address.put("detailAddress", "测试路 1 号");
        address.put("isDefault", 0);
        // 保存地址接口返回 Result.success() 不带 data，需从地址列表取最新一条的 id
        assertOk(post("/address", address, user));
        ResponseEntity<Map> list = get("/address/list", user);
        var records = (java.util.List<Map<String, Object>>) list.getBody().get("data");
        org.assertj.core.api.Assertions.assertThat(records).isNotEmpty();
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Map<String, Object> taskBody(Long addressId) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "任务-" + System.currentTimeMillis());
        body.put("description", "测试描述");
        body.put("reward", 5);
        body.put("categoryId", 1);
        body.put("addressId", addressId);
        body.put("deadline", "2026-12-31 18:00:00");
        return body;
    }

    private Long myLatestTaskId(AuthUser user) {
        ResponseEntity<Map> resp = get("/task/my-published?pageNum=1&pageSize=1", user);
        assertOk(resp);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        var records = (java.util.List<Map<String, Object>>) data.get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Long myLatestApplicationId(AuthUser user, Long taskId) {
        ResponseEntity<Map> resp = get("/task/application/" + taskId + "?pageNum=1&pageSize=1", user);
        assertOk(resp);
        Map<?, ?> data = (Map<?, ?>) resp.getBody().get("data");
        var records = (java.util.List<Map<String, Object>>) data.get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }
}
