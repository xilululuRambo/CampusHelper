package com.rambo.infrastructure;

import com.rambo.BaseApiTest;
import com.rambo.common.constants.EnumConstants;
import com.rambo.common.constants.MessageConstants;
import com.rambo.common.exception.BusinessException;
import com.rambo.helper.AuthUser;
import com.rambo.infrastructure.auth.AdminInterceptor;
import com.rambo.infrastructure.auth.AuthInterceptor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.method.HandlerMethod;

import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 拦截器定向测试：把 {@code AdminInterceptor} / {@code AuthInterceptor} 的分支逐条跑出来。
 *
 * <p><b>为什么需要这组测试</b>：项目的拦截器链是「先 {@code AuthInterceptor} 建身份 →
 * 再 {@code AdminInterceptor} 校验角色」的两段式，但此前只有端到端用例
 * （{@code AdminApiTest#userToken_accessAdmin_denied}）覆盖了「普通用户访问 admin 被拒」
 * 这一条组合路径。以下分支从未被跑到，而它们的失效方式都是**静默放行**（越权而非报错）：</p>
 * <ul>
 *   <li>{@code AdminInterceptor} 的角色白名单漏写 SUPER_ADMIN —— 超管会被自己系统拒之门外；</li>
 *   <li>{@code AdminInterceptor} 误删 OPTIONS 直通 —— 跨域预检被当成越权拦截，前端全站不可用；</li>
 *   <li>{@code AuthInterceptor} 误删 {@code IdHolder.getId()} —— 身份校验形同虚设，全员匿名放行。</li>
 * </ul>
 *
 * <p><b>为什么用直接调用而非 MockMvc 打接口</b>：
 * {@code AdminInterceptor#preHandle} 的入参就是「请求 + handler + ThreadLocal 上下文」三样，
 * 直接构造 {@code MockHttpServletRequest} 传入可以让每个分支被单独命中、断言到具体异常；
 * 走 HTTP 则三种角色只能各构造一套真实登录流程，且 OPTIONS / 非 HandlerMethod 两个分支
 * 根本无法通过控制器路由构造出来。</p>
 *
 * <p>注意：{@code AuthInterceptor} 的「未建立身份」分支通过 {@code IdHolder.getId()}
 * 抛 {@code BusinessException} 实现——它没有显式 if 判断，因此断言必须落在异常上。</p>
 */
class InterceptorTargetedTest extends BaseApiTest {

    @jakarta.annotation.Resource
    private AdminInterceptor adminInterceptor;

    @jakarta.annotation.Resource
    private AuthInterceptor authInterceptor;

    /** 任取一个真实控制器方法，供 handler 参数使用（实例类型必须是 HandlerMethod） */
    private HandlerMethod anyHandlerMethod() throws NoSuchMethodException {
        Method method = InterceptorProbeController.class.getDeclaredMethod("probe");
        return new HandlerMethod(new InterceptorProbeController(), method);
    }

    // ==================== AdminInterceptor：角色白名单 ====================

    @Test
    @DisplayName("AdminInterceptor：普通管理员放行")
    void adminInterceptor_normalAdmin_passes() throws Exception {
        AuthUser user = newAuthedUser();
        roleScenario(user, EnumConstants.ROLE_ADMIN, () ->
                assertThat(adminInterceptor.preHandle(
                        new MockHttpServletRequest("GET", "/admin/user/list"),
                        new MockHttpServletResponse(), anyHandlerMethod()))
                        .as("普通管理员必须在白名单内，否则运营模块全线不可用")
                        .isTrue());
    }

    @Test
    @DisplayName("AdminInterceptor：超级管理员放行（白名单不得漏写 SUPER_ADMIN）")
    void adminInterceptor_superAdmin_passes() throws Exception {
        AuthUser user = newAuthedUser();
        roleScenario(user, EnumConstants.ROLE_SUPER_ADMIN, () ->
                assertThat(adminInterceptor.preHandle(
                        new MockHttpServletRequest("GET", "/admin/list"),
                        new MockHttpServletResponse(), anyHandlerMethod()))
                        .as("超管是最初的管理端身份，漏写会导致超管被自家接口拒绝")
                        .isTrue());
    }

    @Test
    @DisplayName("AdminInterceptor：普通用户角色被拒，异常码为未授权")
    void adminInterceptor_normalUser_denied() throws Exception {
        AuthUser user = newAuthedUser();
        roleScenario(user, EnumConstants.ROLE_USER, () ->
                assertThatThrownBy(() -> adminInterceptor.preHandle(
                        new MockHttpServletRequest("GET", "/admin/user/list"),
                        new MockHttpServletResponse(), anyHandlerMethod()))
                        .as("普通用户即使持有合法 AT 也必须被 admin 拦截器拒绝（身份隔离）")
                        .isInstanceOf(BusinessException.class)
                        .hasMessage(MessageConstants.UNAUTHORIZED));
    }

    @Test
    @DisplayName("AdminInterceptor：无角色上下文（匿名）被拒")
    void adminInterceptor_noRole_denied() throws Exception {
        // RoleHolder 未写入 → getRole() 返回 null，白名单比对必然失败
        assertThatThrownBy(() -> adminInterceptor.preHandle(
                new MockHttpServletRequest("GET", "/admin/user/list"),
                new MockHttpServletResponse(), anyHandlerMethod()))
                .as("匿名请求没有角色上下文，必须走拒绝分支而不是 NPE 或放行")
                .isInstanceOf(BusinessException.class)
                .hasMessage(MessageConstants.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AdminInterceptor：OPTIONS 预检直接放行（跨域可用性的前提）")
    void adminInterceptor_options_passes() throws Exception {
        // 刻意不写角色：若 OPTIONS 分支被误删，本用例会退化为「拒绝」而变红
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/admin/me");
        assertThat(adminInterceptor.preHandle(request, new MockHttpServletResponse(), anyHandlerMethod()))
                .as("预检请求不带鉴权头与角色，被拦截会导致浏览器端所有跨域 admin 请求失败")
                .isTrue();
    }

    @Test
    @DisplayName("AdminInterceptor：非 HandlerMethod（静态资源）直接放行")
    void adminInterceptor_nonHandlerMethod_passes() {
        // 静态资源请求的 handler 是 ResourceHttpRequestHandler，不是 HandlerMethod
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/doc.html");
        assertThat(adminInterceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .as("非控制器方法必须直通，否则文档/静态资源被误拦")
                .isTrue();
    }

    // ==================== AuthInterceptor：身份存在性 ====================

    @Test
    @DisplayName("AuthInterceptor：已有身份放行")
    void authInterceptor_withIdentity_passes() throws Exception {
        AuthUser user = newAuthedUser();
        roleScenario(user, EnumConstants.ROLE_USER, () ->
                assertThat(authInterceptor.preHandle(
                        new MockHttpServletRequest("GET", "/user/me"),
                        new MockHttpServletResponse(), anyHandlerMethod()))
                        .as("上游已建成身份时应放行")
                        .isTrue());
    }

    @Test
    @DisplayName("AuthInterceptor：无身份抛未授权（校验靠 IdHolder 内部抛异常，无显式 if）")
    void authInterceptor_withoutIdentity_throws() throws Exception {
        // 不预先写入 IdHolder —— 这是「JwtInterceptor 宽容放行」后的典型状态
        assertThatThrownBy(() -> authInterceptor.preHandle(
                new MockHttpServletRequest("GET", "/user/me"),
                new MockHttpServletResponse(), anyHandlerMethod()))
                .as("IdHolder 内无值时必须抛异常而非放行；若这里返回 true，"
                        + "则 NoAuthInterceptor 的『已认证』前置条件被绕过，业务接口对匿名开放")
                .isInstanceOf(BusinessException.class)
                .hasMessage(MessageConstants.UNAUTHORIZED);
    }

    @Test
    @DisplayName("AuthInterceptor：OPTIONS 预检放行（不要求身份）")
    void authInterceptor_options_passes() throws Exception {
        assertThat(authInterceptor.preHandle(
                new MockHttpServletRequest("OPTIONS", "/user/me"),
                new MockHttpServletResponse(), anyHandlerMethod()))
                .as("预检无鉴权头，必须在身份校验之前放行")
                .isTrue();
    }

    @Test
    @DisplayName("AuthInterceptor：非 HandlerMethod 直接放行")
    void authInterceptor_nonHandlerMethod_passes() {
        assertThat(authInterceptor.preHandle(
                new MockHttpServletRequest("GET", "/favicon.ico"),
                new MockHttpServletResponse(), new Object()))
                .as("静态资源 handler 非 HandlerMethod，不应被身份校验拦截")
                .isTrue();
    }

    // ==================== 辅助 ====================

    /**
     * 在指定角色上下文下执行断言，执行前后清理 ThreadLocal。
     * <p>拦截器通过 ThreadLocal 传递身份，直接用 @AfterEach 清理会污染 BaseApiTest
     * 既有用例对上下文的假设——因此只在本方法作用域内设置与清理。</p>
     */
    private void roleScenario(AuthUser user, String role, ThrowingRunnable assertion) throws Exception {
        com.rambo.common.context.IdHolder.setId(user.getUserId());
        com.rambo.common.context.RoleHolder.setRole(role);
        try {
            assertion.run();
        } finally {
            com.rambo.common.context.IdHolder.clearId();
            com.rambo.common.context.RoleHolder.clearRole();
        }
    }

    @FunctionalInterface
    private interface ThrowingRunnable {
        void run() throws Exception;
    }

    /** 仅用于提供合法的 HandlerMethod 实例，不参与真实路由 */
    static class InterceptorProbeController {
        public void probe() {
        }
    }
}
