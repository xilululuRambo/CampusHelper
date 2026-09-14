package com.rambo.goods;

import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import com.rambo.module.user.pojo.entity.User;
import com.rambo.module.user.server.service.UserService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品订单全链路状态机测试（串行编排）：
 *   发布 → 购买 → 付款（余额扣减/入账）→ 发货 → 确认收货（商品 SOLD_OUT）→ 双方互评
 * 另含：取消订单恢复商品、并发购买互斥（锁）用例。
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class GoodsOrderApiTest extends BaseApiTest {

    private static final long PRICE = 1000L; // 10 元（分）

    @Resource
    private UserService userService;

    // 串行链路共享状态
    private static AuthUser seller;
    private static AuthUser buyer;
    private static Long goodsId;
    private static Long orderId;

    // ==================== 全链路（@Order 串行） ====================

    @Test
    @Order(1)
    @DisplayName("链路-1：卖家发布商品")
    void step1_publish() {
        seller = newAuthedUser();
        goodsId = publishAndGetId(seller);
    }

    @Test
    @Order(2)
    @DisplayName("链路-2：买家购买，商品转交易中")
    void step2_buy() {
        buyer = newAuthedUser();
        assertOk(post("/goods/buy/" + goodsId, null, buyer));

        // 商品状态 → 交易中(1)
        Map<?, ?> data = (Map<?, ?>) get("/goods/" + goodsId, seller).getBody().get("data");
        assertThat(String.valueOf(data.get("status"))).isEqualTo("1");

        // 从买家订单列表取订单 ID
        orderId = myLatestOrderId(buyer);
    }

    @Test
    @Order(3)
    @DisplayName("链路-3：买家付款，余额扣减、卖家入账")
    void step3_pay() {
        // 测试数据准备：项目无充值接口，直接给买家充值 2000 分（商品价 1000）
        userService.lambdaUpdate()
                .eq(User::getId, buyer.getUserId())
                .set(User::getBalance, PRICE * 2)
                .update();

        assertOk(post("/goods/order/pay/" + orderId, null, buyer));

        // 买家余额 2000-1000=1000，卖家余额 0+1000=1000
        assertThat(balanceOf(buyer)).isEqualTo(PRICE);
        assertThat(balanceOf(seller)).isEqualTo(PRICE);
    }

    @Test
    @Order(4)
    @DisplayName("链路-4：卖家发货")
    void step4_delivery() {
        assertOk(post("/goods/order/delivery/" + orderId, null, seller));
    }

    @Test
    @Order(5)
    @DisplayName("链路-5：买家确认收货，商品转已售出")
    void step5_confirm() {
        assertOk(post("/goods/order/confirm/" + orderId, null, buyer));

        // 商品状态 → 已售出(3)
        Map<?, ?> data = (Map<?, ?>) get("/goods/" + goodsId, seller).getBody().get("data");
        assertThat(String.valueOf(data.get("status"))).isEqualTo("3");
    }

    @Test
    @Order(6)
    @DisplayName("链路-6：订单完成后双方互评")
    void step6_evaluate() {
        Map<String, Object> buyerEval = new HashMap<>();
        buyerEval.put("orderId", orderId);
        buyerEval.put("toUserId", seller.getUserId());
        buyerEval.put("score", 5);
        buyerEval.put("content", "商品很好");
        assertOk(post("/goods/evaluation/add", buyerEval, buyer));

        Map<String, Object> sellerEval = new HashMap<>();
        sellerEval.put("orderId", orderId);
        sellerEval.put("toUserId", buyer.getUserId());
        sellerEval.put("score", 5);
        sellerEval.put("content", "爽快买家");
        assertOk(post("/goods/evaluation/add", sellerEval, seller));

        // 重复评价被拒
        assertFailWithMsg(post("/goods/evaluation/add", buyerEval, buyer), "已评价");
    }

    // ==================== 独立用例 ====================

    @Test
    @Order(7)
    @DisplayName("取消订单：待付款订单取消后商品恢复在售")
    void cancelOrder_restoresGoods() {
        AuthUser s = newAuthedUser();
        Long gId = publishAndGetId(s);
        AuthUser b = newAuthedUser();
        assertOk(post("/goods/buy/" + gId, null, b));
        Long oId = myLatestOrderId(b);

        // 预热详情缓存：先读一次让缓存持有「交易中」；否则缓存为空时取消后回源必然读到新状态，测不出缓存未失效
        Map<?, ?> warmed = (Map<?, ?>) get("/goods/" + gId, s).getBody().get("data");
        assertThat(String.valueOf(warmed.get("status"))).isEqualTo("1");

        assertOk(post("/goods/order/cancel/" + oId, null, b));

        // 商品状态恢复 → 在售(0)（取消必须同步失效详情缓存，否则此处命中缓存仍读到「交易中」）
        Map<?, ?> data = (Map<?, ?>) get("/goods/" + gId, s).getBody().get("data");
        assertThat(String.valueOf(data.get("status"))).isEqualTo("0");
    }

    @Test
    @Order(8)
    @DisplayName("付款：余额不足被拒")
    void pay_insufficientBalance_failed() {
        AuthUser s = newAuthedUser();
        Long gId = publishAndGetId(s);
        AuthUser b = newAuthedUser(); // 余额 0
        assertOk(post("/goods/buy/" + gId, null, b));
        Long oId = myLatestOrderId(b);

        ResponseEntity<Map> resp = post("/goods/order/pay/" + oId, null, b);
        assertFailWithMsg(resp, "余额");
    }

    @Test
    @Order(9)
    @DisplayName("付款：非买家越权被拒")
    void pay_byNonBuyer_denied() {
        AuthUser s = newAuthedUser();
        Long gId = publishAndGetId(s);
        AuthUser b = newAuthedUser();
        AuthUser stranger = newAuthedUser();
        assertOk(post("/goods/buy/" + gId, null, b));
        Long oId = myLatestOrderId(b);

        ResponseEntity<Map> resp = post("/goods/order/pay/" + oId, null, stranger);
        assertFailWithMsg(resp, "无权");
    }

    @Test
    @Order(10)
    @DisplayName("并发购买：两个买家同时购买同一商品，仅一人成功（分布式锁）")
    void concurrentBuy_onlyOneSucceeds() throws Exception {
        AuthUser s = newAuthedUser();
        Long gId = publishAndGetId(s);
        AuthUser b1 = newAuthedUser();
        AuthUser b2 = newAuthedUser();

        AtomicInteger success = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Runnable buyByB1 = () -> fireBuy(gId, b1, start, done, success);
        Runnable buyByB2 = () -> fireBuy(gId, b2, start, done, success);
        pool.submit(buyByB1);
        pool.submit(buyByB2);

        start.countDown();
        assertThat(done.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdown();

        assertThat(success.get())
                .as("分布式锁应保证并发购买只有一个成功")
                .isEqualTo(1);
    }

    private void fireBuy(Long gId, AuthUser buyer, CountDownLatch start,
                         CountDownLatch done, AtomicInteger success) {
        try {
            start.await();
            ResponseEntity<Map> resp = post("/goods/buy/" + gId, null, buyer);
            if (resp.getBody() != null && Integer.valueOf(200).equals(resp.getBody().get("code"))) {
                success.incrementAndGet();
            }
        } catch (Exception ignored) {
        } finally {
            done.countDown();
        }
    }

    // ==================== 私有工具 ====================

    private Long balanceOf(AuthUser user) {
        // /user/me 的 UserPrivateVO 不含 balance 字段，直接从库取
        return userService.getById(user.getUserId()).getBalance();
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
        form.put("title", "订单链路商品-" + System.currentTimeMillis());
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
