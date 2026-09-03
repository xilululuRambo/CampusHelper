package com.rambo.task;

import com.rambo.BaseApiTest;
import com.rambo.helper.AuthUser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static com.rambo.helper.AssertHelper.assertOk;

/**
 * 热门关键词接口 —— 缺陷回归。
 *
 * 【缺陷回归】HotKeywordsController.getHotKeywords 声明为 private 方法，
 * Spring MVC 不会注册 private handler → 该路径实际不可访问。
 * 修复方法：把方法改为 public。修复前本用例红，修复后绿。
 */
class TaskHotKeywordsApiTest extends BaseApiTest {

    @Test
    @DisplayName("回归：GET /task/hot/keywords 应可访问（当前 private 方法 → 红）")
    void hotKeywords_endpointAccessible() {
        AuthUser user = newAuthedUser();
        ResponseEntity<Map> resp = get("/task/hot/keywords", user);
        assertOk(resp);
    }
}
