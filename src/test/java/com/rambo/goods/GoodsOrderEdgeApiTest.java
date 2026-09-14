package com.rambo.goods;

import com.rambo.BaseApiTest;
import com.rambo.module.goods.enums.GoodsStatus;
import com.rambo.helper.AuthUser;
import com.rambo.module.goods.enums.GoodsOrderStatus;
import com.rambo.module.goods.pojo.entity.Goods;
import com.rambo.module.goods.pojo.entity.GoodsOrder;
import com.rambo.module.goods.server.job.GoodsOrderTimeoutJob;
import com.rambo.module.goods.server.service.GoodsOrderService;
import com.rambo.module.goods.server.service.GoodsService;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品订单边界测试：
 *  1) 评价查看越权（IDOR 修复验证，P1-3）
 *  2) 订单详情越权
 *  3) 超时关单 Job：过期待付款订单取消 + 商品恢复（P0-3 CAS 防误取消）
 */
class GoodsOrderEdgeApiTest extends BaseApiTest {

    private static final long PRICE = 1000L;

    @Resource
    private GoodsOrderService goodsOrderService;
    @Resource
    private GoodsService goodsService;
    @Resource
    private UserService userService;
    @Resource
    private GoodsOrderTimeoutJob timeoutJob;

    // ==================== 评价查看越权（IDOR） ====================

    @Test
    @DisplayName("评价查看：订单参与方（买家/卖家）可查看，第三方被拒（IDOR 防护）")
    void evaluationView_onlyParticipants() {
        AuthUser seller = newAuthedUser();
        Long gId = publishAndGetId(seller);
        AuthUser buyer = newAuthedUser();
        assertOk(post("/goods/buy/" + gId, null, buyer));
        Long oId = myLatestOrderId(buyer);

        // 走完订单链路（评价仅限已完成订单）
        userService.lambdaUpdate()
                .eq(User::getId, buyer.getUserId())
                .set(User::getBalance, PRICE * 2)
                .update();
        assertOk(post("/goods/order/pay/" + oId, null, buyer));
        assertOk(post("/goods/order/delivery/" + oId, null, seller));
        assertOk(post("/goods/order/confirm/" + oId, null, buyer));

        // 买家评价后：买家可查看
        Map<String, Object> eval = new HashMap<>();
        eval.put("orderId", oId);
        eval.put("toUserId", seller.getUserId());
        eval.put("score", 5);
        eval.put("content", "好评");
        assertOk(post("/goods/evaluation/add", eval, buyer));
        assertOk(get("/goods/evaluation/" + oId, buyer));
        // 卖家也可查看（另一参与方）
        assertOk(get("/goods/evaluation/" + oId, seller));

        // 第三方（非订单参与方）查看被拒
        AuthUser stranger = newAuthedUser();
        assertFailWithMsg(get("/goods/evaluation/" + oId, stranger), "无权");
        // 未登录同样被拒
        assertFailWithMsg(get("/goods/evaluation/" + oId, null), "未登录");
    }

    // ==================== 订单详情越权 ====================

    @Test
    @DisplayName("订单详情：参与方可查看完整详情，第三方被拒")
    void orderDetail_onlyParticipants() {
        AuthUser seller = newAuthedUser();
        Long gId = publishAndGetId(seller);
        AuthUser buyer = newAuthedUser();
        assertOk(post("/goods/buy/" + gId, null, buyer));
        Long oId = myLatestOrderId(buyer);

        assertOk(get("/goods/order/" + oId, buyer));
        assertOk(get("/goods/order/" + oId, seller));

        AuthUser stranger = newAuthedUser();
        assertFailWithMsg(get("/goods/order/" + oId, stranger), "无权");

        // 不存在的订单
        assertFailWithMsg(get("/goods/order/999999999999999999", buyer), "订单不存在");
    }

    // ==================== 超时关单 Job ====================

    @Test
    @DisplayName("超时关单：过期待付款订单被取消，商品恢复在售")
    void timeoutJob_cancelsExpiredPendingOrder() {
        AuthUser seller = newAuthedUser();
        Long gId = publishAndGetId(seller);
        AuthUser buyer = newAuthedUser();
        assertOk(post("/goods/buy/" + gId, null, buyer));
        Long oId = myLatestOrderId(buyer);

        // 预热详情缓存：先读一次让缓存持有「交易中」，任务恢复状态后必须同步失效缓存
        Map<?, ?> warmed = (Map<?, ?>) get("/goods/" + gId, buyer).getBody().get("data");
        assertThat(String.valueOf(warmed.get("status"))).isEqualTo("1");

        // 把订单创建时间改到超时阈值之前（10 分钟过期）
        expireOrder(oId);

        timeoutJob.execute();

        // 订单状态 → 已取消
        GoodsOrder order = goodsOrderService.getById(oId);
        assertThat(order.getOrderStatus()).isEqualTo(GoodsOrderStatus.CANCELLED);
        // 商品状态 → 恢复在售（走接口读：缓存已预热，验证状态流转同步失效详情缓存）
        Map<?, ?> data = (Map<?, ?>) get("/goods/" + gId, buyer).getBody().get("data");
        assertThat(String.valueOf(data.get("status"))).isEqualTo("0");
        // 数据库状态兜底断言
        assertThat(goodsService.getById(gId).getStatus()).isEqualTo(GoodsStatus.NORMAL);
    }

    @Test
    @DisplayName("超时关单：已付款订单不受影响（CAS 条件防误取消，P0-3）")
    void timeoutJob_skipsPaidOrder() {
        AuthUser seller = newAuthedUser();
        Long gId = publishAndGetId(seller);
        AuthUser buyer = newAuthedUser();
        assertOk(post("/goods/buy/" + gId, null, buyer));
        Long oId = myLatestOrderId(buyer);

        // 买家充值并付款
        userService.lambdaUpdate()
                .eq(User::getId, buyer.getUserId())
                .set(User::getBalance, PRICE * 2)
                .update();
        assertOk(post("/goods/order/pay/" + oId, null, buyer));

        // 即使创建时间已超时，已付款订单也不能被取消（防止钱货两空）
        expireOrder(oId);
        timeoutJob.execute();

        GoodsOrder order = goodsOrderService.getById(oId);
        assertThat(order.getOrderStatus()).isEqualTo(GoodsOrderStatus.PENDING_SHIP);
        // 商品保持交易中
        assertThat(goodsService.getById(gId).getStatus()).isEqualTo(GoodsStatus.TRADING);
    }

    // ==================== 私有工具（与 GoodsOrderApiTest 保持一致） ====================

    /** 将订单创建时间改到超时阈值之前 */
    private void expireOrder(Long orderId) {
        goodsOrderService.lambdaUpdate()
                .eq(GoodsOrder::getId, orderId)
                .set(GoodsOrder::getCreateTime, LocalDateTime.now().minusMinutes(11))
                .update();
    }

    private Long myLatestOrderId(AuthUser user) {
        ResponseEntity<Map> resp = get("/goods/order/my?pageNum=1&pageSize=1", user);
        assertOk(resp);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) resp.getBody().get("data")).get("records");
        assertThat(records).isNotEmpty();
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    private Long publishAndGetId(AuthUser seller) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "边界测试商品-" + System.currentTimeMillis());
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
}
