package com.rambo;

import com.rambo.helper.AssertHelper;
import com.rambo.helper.AuthUser;
import com.rambo.helper.TestAuthHelper;
import com.rambo.infrastructure.messaging.RabbitmqProducer;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import com.rambo.module.goods.server.service.impl.GoodsEsSearchService;
import com.rambo.module.goods.server.service.impl.GoodsEsSyncService;
import com.rambo.module.task.server.service.impl.TaskEsSearchService;
import com.rambo.module.task.server.service.impl.TaskEsSyncService;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;

import static org.mockito.Mockito.when;

import java.util.Map;

/**
 * 接口测试基类：真实端口 + 真实 HTTP + 外部依赖 mock。
 *
 * 外部依赖策略：
 *  - MySQL / Redis / MongoDB → 真实连接（application-test.yml 已切到测试库）
 *  - RabbitMQ / ES / OSS     → 整体 mock，测试不依赖这些中间件可用性
 *  - XXL-JOB 定时任务        → 测试 profile 下 xxl.job.enabled=false，执行器不启动
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class BaseApiTest {

    @LocalServerPort
    protected int port;

    @Resource
    protected TestRestTemplate rest;

    @Resource
    protected StringRedisTemplate redis;

    @Resource
    protected TestAuthHelper authHelper;

    // ============ 外部依赖 mock（声明在父类，所有子测试类生效） ============
    @MockBean
    protected RabbitmqProducer rabbitmqProducer;
    @MockBean
    protected EsUtil esUtil;
    @MockBean
    protected GoodsEsSyncService goodsEsSyncService;
    @MockBean
    protected TaskEsSyncService taskEsSyncService;
    @MockBean
    protected GoodsEsSearchService goodsEsSearchService;
    @MockBean
    protected TaskEsSearchService taskEsSearchService;
    @MockBean
    protected AliyunOssUtil aliyunOssUtil;

    // ==================== 请求构造 ====================

    protected String baseUrl() {
        return "http://localhost:" + port + "/api";
    }

    /** 带登录身份的请求头 */
    protected HttpHeaders authHeaders(AuthUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (user != null) {
            headers.setBearerAuth(user.getAccessToken());
        }
        return headers;
    }

    protected ResponseEntity<Map> get(String path, AuthUser user) {
        return rest.exchange(baseUrl() + path, HttpMethod.GET,
                new HttpEntity<>(authHeaders(user)), Map.class);
    }

    protected ResponseEntity<Map> post(String path, Object body, AuthUser user) {
        HttpEntity<Object> entity = new HttpEntity<>(body, authHeaders(user));
        return rest.exchange(baseUrl() + path, HttpMethod.POST, entity, Map.class);
    }

    protected ResponseEntity<Map> put(String path, Object body, AuthUser user) {
        HttpEntity<Object> entity = new HttpEntity<>(body, authHeaders(user));
        return rest.exchange(baseUrl() + path, HttpMethod.PUT, entity, Map.class);
    }

    protected ResponseEntity<Map> delete(String path, AuthUser user) {
        return rest.exchange(baseUrl() + path, HttpMethod.DELETE,
                new HttpEntity<>(authHeaders(user)), Map.class);
    }

    /** multipart 表单请求（图片上传、完成证据等场景） */
    protected ResponseEntity<Map> postMultipart(String path, Map<String, Object> form, AuthUser user) {
        return requestMultipart(path, HttpMethod.POST, form, user);
    }

    // ==================== 防重复提交令牌支持 ====================

    /** 获取防重复提交令牌（@PreventDuplicate 接口前置调用） */
    protected String submitToken(String scene, AuthUser user) {
        ResponseEntity<Map> resp = get("/common/submit-token?scene=" + scene, user);
        AssertHelper.assertOk(resp);
        return (String) resp.getBody().get("data");
    }

    /** 带防重复提交令牌的 POST 请求 */
    protected ResponseEntity<Map> postWithSubmitToken(String path, Object body, AuthUser user, String scene) {
        HttpHeaders headers = authHeaders(user);
        headers.add("X-Submit-Token", submitToken(scene, user));
        return rest.exchange(baseUrl() + path, HttpMethod.POST,
                new HttpEntity<>(body, headers), Map.class);
    }

    protected ResponseEntity<Map> putMultipart(String path, Map<String, Object> form, AuthUser user) {
        return requestMultipart(path, HttpMethod.PUT, form, user);
    }

    private ResponseEntity<Map> requestMultipart(String path, HttpMethod method,
                                                 Map<String, Object> form, AuthUser user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        if (user != null) {
            headers.setBearerAuth(user.getAccessToken());
        }
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        form.forEach(body::add);
        return rest.exchange(baseUrl() + path, method,
                new HttpEntity<>(body, headers), Map.class);
    }

    // ==================== 快捷注册登录 ====================

    /** 注册并登录一个普通用户（未做学生认证） */
    protected AuthUser newUser() {
        return authHelper.registerAndLogin();
    }

    /** 注册 + 登录 + 学生认证（业务接口要求 authStatus=VERIFIED 用户） */
    protected AuthUser newAuthedUser() {
        return authHelper.registerAndLoginWithAuth();
    }

    // ==================== 通用断言透传 ====================

    protected void assertOk(ResponseEntity<Map> resp) {
        AssertHelper.assertOk(resp);
    }

    protected void assertFail(ResponseEntity<Map> resp) {
        AssertHelper.assertFail(resp);
    }

    protected void assertFailWithMsg(ResponseEntity<Map> resp, String keyword) {
        AssertHelper.assertFailWithMsg(resp, keyword);
    }

    /** 每个用例结束后重置 Mockito mock，避免 stub 状态泄漏到下一个用例 */
    @org.junit.jupiter.api.AfterEach
    void resetMocks() {
        org.mockito.Mockito.reset(esUtil, goodsEsSyncService, taskEsSyncService,
                goodsEsSearchService, taskEsSearchService, rabbitmqProducer, aliyunOssUtil);
    }

    /**
     * 每个用例开始前 stub OSS 行为：
     * 上传返回假文件名/假 URL（mock 默认返回空列表会导致商品 images 为空串、
     * 被列表 VO 过滤掉；返回 null 会导致 setAvatar 等 NPE）。
     */
    @org.junit.jupiter.api.BeforeEach
    void stubOss() {
        when(aliyunOssUtil.uploadFilesAndGetNames(org.mockito.ArgumentMatchers.any()))
                .thenReturn(java.util.List.of("mock-img-1.jpg", "mock-img-2.jpg"));
        when(aliyunOssUtil.upload(org.mockito.ArgumentMatchers.any()))
                .thenReturn("mock-upload.jpg");
        when(aliyunOssUtil.getUrl(org.mockito.ArgumentMatchers.any()))
                .thenReturn("https://mock.oss.example.com/mock.jpg");
    }
}
