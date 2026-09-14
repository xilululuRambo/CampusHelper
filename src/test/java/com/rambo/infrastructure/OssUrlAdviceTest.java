package com.rambo.infrastructure;

import com.rambo.common.annotation.OssUrl;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import com.rambo.infrastructure.web.OssUrlResponseBodyAdvice;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * OSS URL 响应出口签名测试：{@code OssUrlResponseBodyAdvice}。
 *
 * <p><b>为什么这是安全相关的</b>：该 advice 是「签名 URL 唯一出口」。
 * 项目把 {@code @OssUrl} 字段在缓存层与 DB 里统一存 objectName，靠这个出口在响应瞬间
 * 换成 30 分钟临时签名 URL。三种失效方式后果都不轻：</p>
 * <ul>
 *   <li><b>漏签</b>（递归没走到底）→ 前端拿到裸 objectName，图片 404；</li>
 *   <li><b>重复签</b>（没跳过已是 http 的值）→ 把签名 URL 当 objectName 再签一次，
 *       生成注定无效的 URL，且会污染 AliyunOssUtil 的短缓存；</li>
 *   <li><b>漏了剪枝优化</b>（supports 永远返回 true）→ 每个 String/Number 返回值
 *       都进 advice 走一次反射，属于白白的性能损失（P2-8 的优化点）。</li>
 * </ul>
 *
 * <p>本测试用 Mockito 替身 {@code AliyunOssUtil}（真实签名需 OSS 凭据与网络），
 * 但 advice 本身、字段反射、递归逻辑、类元数据缓存全部走真实实现——
 * 换掉 mock 就是真实行为，可放心用作回归保护。</p>
 */
class OssUrlAdviceTest {

    private static final String SIGNED = "https://test-bucket.oss-cn-beijing.aliyuncs.com/";

    private OssUrlResponseBodyAdvice advice;
    private AliyunOssUtil ossUtil;

    @BeforeEach
    void setUp() {
        advice = new OssUrlResponseBodyAdvice();
        ossUtil = mock(AliyunOssUtil.class);
        // 签名规则：objectName 前面拼上 bucket 域名，便于断言"哪个值被签了"
        when(ossUtil.getUrl(anyString())).thenAnswer(inv ->
                SIGNED + inv.getArgument(0) + "?Signature=mock");
        ReflectionTestUtils.setField(advice, "aliyunOssUtil", ossUtil);
    }

    private Object applyAdvice(Object body) throws Exception {
        Method m = OssUrlResponseBodyAdvice.class.getMethod("beforeBodyWrite",
                Object.class, MethodParameter.class, MediaType.class, Class.class,
                ServerHttpRequest.class, ServerHttpResponse.class);
        return m.invoke(advice, body, null, null, null, null, null);
    }

    // ==================== 支持的返回值类型 ====================

    @Test
    @DisplayName("supports：值类型返回值直接短路（避免无谓进入 advice）")
    void supports_valueTypes_shortCircuited() throws Exception {
        for (Class<?> clazz : List.of(String.class, Integer.class, Long.class, Boolean.class, byte[].class)) {
            MethodParameter mp = methodParameterReturning(clazz);
            assertThat(advice.supports(mp, null))
                    .as("%s 不可能携带 @OssUrl 字段，supports 必须返回 false——"
                            + "这是 P2-8 剪枝优化的核心，返回 true 会让每个接口都白走一遍 advice", clazz.getSimpleName())
                    .isFalse();
        }
    }

    @Test
    @DisplayName("supports：自定义类型返回 true（可能携带 @OssUrl）")
    void supports_customType_returnsTrue() throws Exception {
        assertThat(advice.supports(methodParameterReturning(List.class), null))
                .as("集合/自定义 VO 内部可能嵌套 @OssUrl 字段，必须进入 advice")
                .isTrue();
        assertThat(advice.supports(methodParameterReturning(VoWithSingleUrl.class), null)).isTrue();
    }

    // ==================== 单字段签名 ====================

    @Test
    @DisplayName("单字段签名：objectName 被换成签名 URL")
    void singleField_signed() throws Exception {
        VoWithSingleUrl vo = new VoWithSingleUrl();
        vo.avatar = "avatars/user-1.png";

        applyAdvice(vo);

        assertThat(vo.avatar)
                .as("标注 @OssUrl 的字段必须在响应出口换成签名 URL")
                .isEqualTo(SIGNED + "avatars/user-1.png?Signature=mock");
    }

    @Test
    @DisplayName("已是 http(s) 的值跳过二次签名（防止把签名 URL 再签一次）")
    void alreadyHttpUrl_skipped() throws Exception {
        String defaultAvatar = "https://third-party.cdn.com/default-avatar.png";
        VoWithSingleUrl vo = new VoWithSingleUrl();
        vo.avatar = defaultAvatar;

        applyAdvice(vo);

        assertThat(vo.avatar)
                .as("默认头像等外链必须原样透出。若被二次签名，会生成一个注定无效的 URL，"
                        + "同时把外链当作 objectName 写进签名缓存")
                .isEqualTo(defaultAvatar);
    }

    @Test
    @DisplayName("空白值跳过签名（不要为空字符串生成 URL）")
    void blankValue_skipped() throws Exception {
        VoWithSingleUrl vo = new VoWithSingleUrl();
        vo.avatar = "   ";

        applyAdvice(vo);

        assertThat(vo.avatar).as("空白 objectName 不应产生签名 URL").isEqualTo("   ");
    }

    @Test
    @DisplayName("null 值安全跳过，不抛 NPE")
    void nullValue_skipped() throws Exception {
        VoWithSingleUrl vo = new VoWithSingleUrl();
        vo.avatar = null;

        applyAdvice(vo);   // 不抛异常即通过

        assertThat(vo.avatar).isNull();
    }

    // ==================== List 形态 ====================

    @Test
    @DisplayName("List<String> 字段：逐元素签名，顺序保持不变")
    void listField_signedKeepingOrder() throws Exception {
        VoWithListUrl vo = new VoWithListUrl();
        vo.images = new ArrayList<>(Arrays.asList("goods/a.jpg", "goods/b.jpg", "goods/c.jpg"));

        applyAdvice(vo);

        assertThat(vo.images).containsExactly(
                SIGNED + "goods/a.jpg?Signature=mock",
                SIGNED + "goods/b.jpg?Signature=mock",
                SIGNED + "goods/c.jpg?Signature=mock");
        assertThat(vo.images)
                .as("图片顺序决定前端展示顺序，签名不得打乱（不可用 Set 承载）")
                .hasSize(3);
    }

    @Test
    @DisplayName("List 中混有 http 外链：只签需要签的，外链原样保留")
    void listField_mixedHttp_preserved() throws Exception {
        VoWithListUrl vo = new VoWithListUrl();
        vo.images = new ArrayList<>(Arrays.asList(
                "goods/a.jpg", "https://cdn.example.com/b.png", ""));

        applyAdvice(vo);

        assertThat(vo.images.get(0)).isEqualTo(SIGNED + "goods/a.jpg?Signature=mock");
        assertThat(vo.images.get(1)).as("外链不签名").isEqualTo("https://cdn.example.com/b.png");
        assertThat(vo.images.get(2)).as("空字符串原样保留，不产生 URL").isEmpty();
    }

    @Test
    @DisplayName("List 为空或 null 时不崩、不改动")
    void listField_emptyOrNull_safe() throws Exception {
        VoWithListUrl empty = new VoWithListUrl();
        empty.images = new ArrayList<>();
        applyAdvice(empty);
        assertThat(empty.images).isEmpty();

        VoWithListUrl nullImages = new VoWithListUrl();
        nullImages.images = null;
        applyAdvice(nullImages);
        assertThat(nullImages.images).isNull();
    }

    // ==================== 逗号分隔多图 ====================

    @Test
    @DisplayName("逗号分隔多图字符串：逐段签名后用逗号拼回")
    void commaSeparated_signed() throws Exception {
        VoWithCommaUrl vo = new VoWithCommaUrl();
        vo.images = "orders/x.jpg,orders/y.jpg";

        applyAdvice(vo);

        assertThat(vo.images)
                .as("订单快照用逗号分隔存多图，签名后必须保持同样的分隔格式——"
                        + "否则前端按逗号切分时会拿到一个残缺的 URL")
                .isEqualTo(SIGNED + "orders/x.jpg?Signature=mock," + SIGNED + "orders/y.jpg?Signature=mock");
    }

    @Test
    @DisplayName("逗号分隔中夹带外链与空段：外链保留、空段被丢弃")
    void commaSeparated_mixedInput_droppedEmptySegments() throws Exception {
        VoWithCommaUrl vo = new VoWithCommaUrl();
        vo.images = "orders/x.jpg,,https://cdn.example.com/y.png";

        applyAdvice(vo);

        assertThat(vo.images)
                .as("空段必须在签名时被过滤（原实现 filter(StringUtils::hasText)），"
                        + "否则会留下连续的逗号造成前端渲染空图")
                .isEqualTo(SIGNED + "orders/x.jpg?Signature=mock,https://cdn.example.com/y.png");
    }

    // ==================== 递归 ====================

    @Test
    @DisplayName("递归：嵌套 VO 里的 @OssUrl 也要被签（Result → VO → 嵌套）")
    void nestedVo_recursivelySigned() throws Exception {
        OuterVo outer = new OuterVo();
        outer.name = "外层";
        outer.inner = new VoWithSingleUrl();
        outer.inner.avatar = "avatars/nested.png";

        applyAdvice(outer);

        assertThat(outer.inner.avatar)
                .as("响应体常是 Result{data: VO} 的多层嵌套，递归必须走到底，否则内层头像仍是裸 objectName")
                .isEqualTo(SIGNED + "avatars/nested.png?Signature=mock");
    }

    @Test
    @DisplayName("递归：集合中的元素逐个处理")
    void collectionElements_processed() throws Exception {
        VoWithSingleUrl a = new VoWithSingleUrl();
        a.avatar = "avatars/a.png";
        VoWithSingleUrl b = new VoWithSingleUrl();
        b.avatar = "avatars/b.png";

        applyAdvice(new ArrayList<>(Arrays.asList(a, b)));

        assertThat(a.avatar).isEqualTo(SIGNED + "avatars/a.png?Signature=mock");
        assertThat(b.avatar).isEqualTo(SIGNED + "avatars/b.png?Signature=mock");
    }

    @Test
    @DisplayName("递归：Map 只处理 value（key 是业务标识，不应被改写）")
    void mapValues_processedKeysUntouched() throws Exception {
        Map<String, Object> map = new LinkedHashMap<>();
        VoWithSingleUrl vo = new VoWithSingleUrl();
        vo.avatar = "avatars/m.png";
        map.put("avatars/m.png", vo);   // key 刻意也写成 objectName 形态，验证不会被改写

        applyAdvice(map);

        assertThat(vo.avatar).isEqualTo(SIGNED + "avatars/m.png?Signature=mock");
        assertThat(map).containsOnlyKeys("avatars/m.png");
    }

    @Test
    @DisplayName("继承层级：父类声明的 @OssUrl 字段同样被处理")
    void inheritedField_signed() throws Exception {
        ChildVo child = new ChildVo();
        child.avatar = "avatars/inherited.png";   // 声明在父类
        child.extra = "子类字段";

        applyAdvice(child);

        assertThat(child.avatar)
                .as("UserPrivateVO extends UserPublicVO 这类结构依赖父类字段遍历，"
                        + "buildClassMeta 必须沿 getSuperclass() 向上收集")
                .isEqualTo(SIGNED + "avatars/inherited.png?Signature=mock");
    }

    @Test
    @DisplayName("null 响应体与值类型元素不抛异常")
    void nullBodyAndValueElements_safe() throws Exception {
        assertThat(applyAdvice(null)).as("404 等场景 data 可能为 null").isNull();
        assertThat(applyAdvice("plain string")).isEqualTo("plain string");
        assertThat(applyAdvice(123)).isEqualTo(123);
    }

    // ==================== 类元数据缓存 ====================

    @Test
    @DisplayName("类元数据缓存：同类型重复处理结果一致（反射只做一次）")
    void classMetaCache_reusedAcrossCalls() throws Exception {
        VoWithSingleUrl first = new VoWithSingleUrl();
        first.avatar = "a.png";
        VoWithSingleUrl second = new VoWithSingleUrl();
        second.avatar = "b.png";

        applyAdvice(first);
        applyAdvice(second);

        assertThat(first.avatar).isEqualTo(SIGNED + "a.png?Signature=mock");
        assertThat(second.avatar)
                .as("缓存命中后第二个实例必须同样被处理——若缓存把字段列表缓存成了空，"
                        + "首次之后的同类响应都会漏签（间歇性图片 404 的典型成因）")
                .isEqualTo(SIGNED + "b.png?Signature=mock");
    }

    // ==================== 测试用 VO ====================

    static class VoWithSingleUrl {
        @OssUrl
        String avatar;
        String title = "不需要签名的字段";
    }

    static class VoWithListUrl {
        @OssUrl
        List<String> images;
    }

    static class VoWithCommaUrl {
        @OssUrl
        String images;
    }

    static class ParentVo {
        @OssUrl
        String avatar;
    }

    static class ChildVo extends ParentVo {
        String extra;
    }

    static class OuterVo {
        String name;
        VoWithSingleUrl inner;
    }

    /** 取一个返回指定类型的 MethodParameter（supports 只读返回类型，不关心方法体） */
    private MethodParameter methodParameterReturning(Class<?> returnType) throws Exception {
        Method method = ReturnTypeHolder.class.getDeclaredMethod("hold");
        MethodParameter mp = new MethodParameter(method, -1);
        ReflectionTestUtils.setField(mp, "parameterType", returnType);
        return mp;
    }

    static class ReturnTypeHolder {
        public Object hold() {
            return null;
        }
    }
}
