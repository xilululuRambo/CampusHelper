package com.rambo.common;

import com.rambo.BaseApiTest;
import com.rambo.common.constants.RedisKeyConstants;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.rambo.helper.AssertHelper.assertFailWithMsg;
import static com.rambo.helper.AssertHelper.assertOk;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * 公共接口测试：防重复提交令牌。
 */
class CommonApiTest extends BaseApiTest {

    @Test
    @DisplayName("获取防重复提交令牌：成功且 Redis 存在对应 key")
    void submitToken_success_storedInRedis() {
        AuthUser user = newAuthedUser();
        ResponseEntity<Map> resp = get("/common/submit-token?scene=publishTask", user);
        assertOk(resp);

        // data 就是 token 字符串本身（Result.success(token)）
        String token = (String) resp.getBody().get("data");
        String key = RedisKeyConstants.SUBMIT_TOKEN_PREFIX + "publishTask:" + user.getUserId() + ":" + token;
        assertThat(redis.hasKey(key)).as("令牌应写入 Redis").isTrue();
    }

    @Test
    @DisplayName("获取防重复提交令牌：未登录被拒")
    void submitToken_withoutLogin_unauthorized() {
        ResponseEntity<Map> resp = get("/common/submit-token?scene=publishTask", null);
        assertFailWithMsg(resp, "未登录");
    }

    @Test
    @DisplayName("获取防重复提交令牌：scene 缺失被拒")
    void submitToken_missingScene_failed() {
        AuthUser user = newUser();
        ResponseEntity<Map> resp = get("/common/submit-token", user);
        com.rambo.helper.AssertHelper.assertFail(resp);
    }
}
