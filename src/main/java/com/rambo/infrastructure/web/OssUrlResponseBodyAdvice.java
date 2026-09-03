package com.rambo.infrastructure.web;

import com.rambo.common.annotation.OssUrl;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.converter.HttpMessageConverter;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 响应出口统一签名：
 * 将响应体中标注 {@link OssUrl} 的字段（OSS objectName）在返回前端前统一签名成临时访问 URL。
 * <p>
 * 设计要点：
 * 1. 缓存层（@Cacheable）只缓存 objectName，与签名 URL 的 30 分钟有效期完全解耦；
 * 2. URL 从"响应时刻"起算 30 分钟，有效期最大化；
 * 3. 同一 objectName 在有效期内复用同一签名（AliyunOssUtil 内部短缓存），保证 URL 稳定；
 * 4. 已是 http(s):// 开头的值（如默认头像）自动跳过，不做二次签名。
 * <p>
 * 性能优化（P2-8）：
 * 每个类仅反射一次（{@link ClassMeta} 缓存），后续同类型响应直接复用字段元数据，
 * 避免原先"每个响应递归 getDeclaredFields"的重复反射开销；
 * 对无嵌套价值的值类型返回值（String/Number/byte[] 等）在 supports() 直接短路，不进 advice。
 */
@Slf4j
@RestControllerAdvice
public class OssUrlResponseBodyAdvice implements ResponseBodyAdvice<Object> {

    private static final String HTTP_PREFIX = "http://";
    private static final String HTTPS_PREFIX = "https://";

    /**
     * 类字段元数据缓存：class -> 该类的 OssUrl 标注字段 + 需递归处理的非值类型字段。
     * 字段结构在类加载后不可变，缓存安全；key 为 Class，无泄漏风险（由 AppClassLoader 长期持有）。
     */
    private record ClassMeta(List<Field> ossFields, List<Field> nestedFields) {}

    private final Map<Class<?>, ClassMeta> classMetaCache = new ConcurrentHashMap<>();

    @Resource
    private AliyunOssUtil aliyunOssUtil;

    @Override
    public boolean supports(MethodParameter returnType, Class<? extends HttpMessageConverter<?>> converterType) {
        // 值类型返回值（String/Number/byte[] 等）不可能携带 @OssUrl 字段，直接短路，避免无谓进入 advice
        Class<?> returnClazz = returnType.getParameterType();
        return returnClazz != null && !isValueTypeClass(returnClazz);
    }

    @Override
    public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                  Class<? extends HttpMessageConverter<?>> selectedConverterType,
                                  ServerHttpRequest request, ServerHttpResponse response) {
        fillOssUrls(body);
        return body;
    }

    /**
     * 递归处理响应体：
     * 对标注 {@link OssUrl} 的字段签名；对嵌套结构（Result/PageResult/List/VO）递归遍历。
     * 每个类只做一次反射，元数据缓存在 {@link #classMetaCache}。
     *
     * @param obj 待处理对象
     */
    private void fillOssUrls(Object obj) {
        if (obj == null) {
            return;
        }
        // 集合：逐元素处理
        if (obj instanceof Collection<?> collection) {
            collection.forEach(this::fillOssUrls);
            return;
        }
        // Map：只处理值
        if (obj instanceof Map<?, ?> map) {
            map.values().forEach(this::fillOssUrls);
            return;
        }
        // 基础类型/值类型：无需处理
        if (isValueType(obj)) {
            return;
        }
        ClassMeta meta = classMetaCache.computeIfAbsent(obj.getClass(), this::buildClassMeta);
        // 1. 签名 @OssUrl 标注字段
        for (Field field : meta.ossFields()) {
            try {
                fillOssUrlField(obj, field, field.get(obj));
            } catch (IllegalAccessException e) {
                log.warn("OSS URL 签名跳过字段 {}: {}", field.getName(), e.getMessage());
            }
        }
        // 2. 递归处理嵌套对象（仅限非值类型字段，剪掉整棵值类型子树）
        for (Field field : meta.nestedFields()) {
            try {
                fillOssUrls(field.get(obj));
            } catch (IllegalAccessException e) {
                log.warn("OSS URL 递归跳过字段 {}: {}", field.getName(), e.getMessage());
            }
        }
    }

    /**
     * 构建类元数据：遍历类层级（含父类字段，如 UserPrivateVO extends UserPublicVO），
     * 收集 @OssUrl 标注字段与需递归的非值类型字段。
     */
    private ClassMeta buildClassMeta(Class<?> clazz) {
        List<Field> ossFields = new ArrayList<>();
        List<Field> nestedFields = new ArrayList<>();
        for (Class<?> c = clazz; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                // 跳过静态字段
                if (Modifier.isStatic(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                if (field.isAnnotationPresent(OssUrl.class)) {
                    ossFields.add(field);
                } else if (!isValueTypeClass(field.getType())) {
                    // 非值类型字段可能嵌套携带 @OssUrl，需递归
                    nestedFields.add(field);
                }
            }
        }
        return new ClassMeta(ossFields, nestedFields);
    }

    /**
     * 对单个 {@link OssUrl} 字段签名，支持 String / List&lt;String&gt; 两种形态
     */
    private void fillOssUrlField(Object owner, Field field, Object value) throws IllegalAccessException {
        if (value instanceof String str) {
            if (!StringUtils.hasText(str) || isHttpUrl(str)) {
                return;
            }
            if (str.contains(",")) {
                // 逗号分隔多图（如订单快照 images："a.jpg,b.jpg"）
                String joined = Arrays.stream(str.split(","))
                        .filter(StringUtils::hasText)
                        .map(item -> isHttpUrl(item) ? item : aliyunOssUtil.getUrl(item))
                        .collect(Collectors.joining(","));
                field.set(owner, joined);
            } else {
                field.set(owner, aliyunOssUtil.getUrl(str));
            }
        } else if (value instanceof List<?> list) {
            List<Object> urls = new ArrayList<>(list.size());
            for (Object item : list) {
                if (item instanceof String s && StringUtils.hasText(s) && !isHttpUrl(s)) {
                    urls.add(aliyunOssUtil.getUrl(s));
                } else {
                    urls.add(item);
                }
            }
            field.set(owner, urls);
        }
    }

    /**
     * 判断是否为无需签名的值类型（运行时实例判断，处理 List/Map 元素）
     */
    private boolean isValueType(Object obj) {
        return obj instanceof String || obj instanceof Number || obj instanceof Boolean
                || obj instanceof Character || obj instanceof Enum<?>
                || obj instanceof byte[] || obj.getClass().isPrimitive()
                || obj instanceof java.time.temporal.TemporalAccessor
                || obj instanceof java.util.Date;
    }

    /**
     * 判断类型是否为无需签名的值类型（编译期类型判断，用于字段剪枝与 supports 短路）
     */
    private boolean isValueTypeClass(Class<?> clazz) {
        return clazz == String.class || clazz == byte[].class || clazz.isPrimitive()
                || Number.class.isAssignableFrom(clazz)
                || Boolean.class.isAssignableFrom(clazz)
                || Character.class.isAssignableFrom(clazz)
                || Enum.class.isAssignableFrom(clazz)
                || java.time.temporal.TemporalAccessor.class.isAssignableFrom(clazz)
                || java.util.Date.class.isAssignableFrom(clazz);
    }

    /**
     * 判断是否已是可直接访问的 URL（跳过签名）
     */
    private boolean isHttpUrl(String s) {
        return s.startsWith(HTTP_PREFIX) || s.startsWith(HTTPS_PREFIX);
    }
}
