package com.rambo.goods;

import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.ResponseEntity;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 商品模块接口测试：发布（multipart 多图）/ 更新 / 状态 / 详情 / 删除 / 购买。
 * 图片上传链路 OSS 已 mock（AliyunOssUtil.upload 返回假名），仅验证接口与校验逻辑。
 */
class GoodsApiTest extends BaseApiTest {

    private Map<String, Object> goodsForm(Long categoryId) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "测试商品-" + System.currentTimeMillis());
        form.put("description", "九成新，功能完好");
        form.put("categoryId", String.valueOf(categoryId == null ? 1 : categoryId));
        form.put("price", "1000"); // 单位分 = 10 元
        form.put("images", image("front.jpg"));
        form.put("images", image("back.jpg"));
        return form;
    }

    private ByteArrayResource image(String filename) {
        return new ByteArrayResource("fake-image-bytes".getBytes()) {
            @Override
            public String getFilename() {
                return filename;
            }
        };
    }

    private Long publishAndGetId(AuthUser seller) {
        ResponseEntity<Map> resp = postMultipart("/goods", goodsForm(1L), seller);
        assertOk(resp);
        ResponseEntity<Map> my = get("/goods/my?pageNum=1&pageSize=1", seller);
        assertOk(my);
        List<Map<String, Object>> records =
                (List<Map<String, Object>>) ((Map<?, ?>) my.getBody().get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    @Test
    @DisplayName("发布商品：多图正常发布")
    void publishGoods_withImages_success() {
        AuthUser seller = newAuthedUser();
        Long goodsId = publishAndGetId(seller);

        ResponseEntity<Map> detail = get("/goods/" + goodsId, seller);
        assertOk(detail);
        assertThat(String.valueOf(((Map<?, ?>) detail.getBody().get("data")).get("title")))
                .startsWith("测试商品");
    }

    @Test
    @DisplayName("发布商品：无图被拒")
    void publishGoods_withoutImages_failed() {
        AuthUser seller = newAuthedUser();
        Map<String, Object> form = goodsForm(1L);
        form.remove("images");
        ResponseEntity<Map> resp = postMultipart("/goods", form, seller);
        // 注：@RequestPart(required=true) 缺失时抛 Spring 异常，消息为"系统错误"而非业务提示（API 改进点）
        assertFail(resp);
    }

    @Test
    @DisplayName("发布商品：图片扩展名非法被拒")
    void publishGoods_invalidImageType_failed() {
        AuthUser seller = newAuthedUser();
        Map<String, Object> form = goodsForm(1L);
        form.put("images", image("virus.exe"));
        ResponseEntity<Map> resp = postMultipart("/goods", form, seller);
        assertFailWithMsg(resp, "格式");
    }

    @Test
    @DisplayName("发布商品：分类不存在被拒")
    void publishGoods_categoryNotExist_failed() {
        AuthUser seller = newAuthedUser();
        ResponseEntity<Map> resp = postMultipart("/goods", goodsForm(99999L), seller);
        assertFailWithMsg(resp, "分类");
    }

    @Test
    @DisplayName("更新商品：非本人越权被拒")
    void updateGoods_byNonOwner_denied() {
        AuthUser seller = newAuthedUser();
        AuthUser other = newAuthedUser();
        Long goodsId = publishAndGetId(seller);

        Map<String, Object> form = goodsForm(1L);
        ResponseEntity<Map> resp = putMultipart("/goods/" + goodsId, form, other);
        assertFailWithMsg(resp, "无权");
    }

    @Test
    @DisplayName("更新商品状态：目标状态非法（TRADING/SOLD_OUT）被拒")
    void updateGoodsStatus_invalidTarget_failed() {
        AuthUser seller = newAuthedUser();
        Long goodsId = publishAndGetId(seller);

        // 枚举参数走数字 code（1=TRADING），业务校验应拒绝该非法目标状态
        ResponseEntity<Map> resp = put("/goods/status/" + goodsId + "?goodsStatus=1", null, seller);
        assertFailWithMsg(resp, "锁定");
    }

    @Test
    @DisplayName("删除商品：非本人越权被拒")
    void deleteGoods_byNonOwner_denied() {
        AuthUser seller = newAuthedUser();
        AuthUser other = newAuthedUser();
        Long goodsId = publishAndGetId(seller);

        ResponseEntity<Map> resp = delete("/goods/" + goodsId, other);
        assertFailWithMsg(resp, "无权");
    }

    @Test
    @DisplayName("商品详情：未登录被拒")
    void getGoods_withoutToken_unauthorized() {
        ResponseEntity<Map> resp = get("/goods/1", null);
        assertFailWithMsg(resp, "未登录");
    }

    @Test
    @DisplayName("购买：不能买自己的商品")
    void buyGoods_ownGoods_denied() {
        AuthUser seller = newAuthedUser();
        Long goodsId = publishAndGetId(seller);

        ResponseEntity<Map> resp = post("/goods/buy/" + goodsId, null, seller);
        assertFailWithMsg(resp, "无权");
    }
}
