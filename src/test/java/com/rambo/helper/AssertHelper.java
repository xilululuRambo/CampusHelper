package com.rambo.helper;

import org.springframework.http.ResponseEntity;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 统一断言工具：三层断言规范
 *   1) HTTP 状态（非 5xx 即可，项目全局异常统一返回 200+业务码）
 *   2) 业务 code（200=成功）
 *   3) data 关键字段
 */
public final class AssertHelper {

    private AssertHelper() {
    }

    /** 业务成功：code == 200 */
    public static void assertOk(ResponseEntity<Map> resp) {
        assertThat(resp.getBody()).isNotNull();
        assertThat((Integer) resp.getBody().get("code"))
                .as("业务码应为 200，实际响应: %s", resp.getBody())
                .isEqualTo(200);
    }

    /** 业务失败：code != 200（参数错误/业务异常/越权等，按 msg 进一步断言） */
    public static void assertFail(ResponseEntity<Map> resp) {
        assertThat(resp.getBody()).isNotNull();
        assertThat((Integer) resp.getBody().get("code"))
                .as("业务码不应为 200，实际响应: %s", resp.getBody())
                .isNotEqualTo(200);
    }

    /** 业务失败且消息包含指定关键字 */
    public static void assertFailWithMsg(ResponseEntity<Map> resp, String keyword) {
        assertFail(resp);
        assertThat((String) resp.getBody().get("msg")).contains(keyword);
    }

    /** 成功且 data 非空 */
    @SuppressWarnings("unchecked")
    public static void assertOkWithData(ResponseEntity<Map> resp) {
        assertOk(resp);
        assertThat(resp.getBody().get("data")).as("data 不应为空").isNotNull();
    }

    /** 分页响应：total/records 存在 */
    @SuppressWarnings("unchecked")
    public static void assertPage(ResponseEntity<Map> resp, long expectedTotal) {
        assertOk(resp);
        Map<String, Object> data = (Map<String, Object>) resp.getBody().get("data");
        assertThat(data).as("分页 data 不应为空").isNotNull();
        assertThat(((Number) data.get("total")).longValue()).isEqualTo(expectedTotal);
        assertThat((List<?>) data.get("records")).isNotNull();
    }

    /** 未登录（无 token / token 失效）→ 业务 code != 200，msg 含「未登录」 */
    public static void assertUnauthorized(ResponseEntity<Map> resp) {
        assertFailWithMsg(resp, "未登录");
    }
}
