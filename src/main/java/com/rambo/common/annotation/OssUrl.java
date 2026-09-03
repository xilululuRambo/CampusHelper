package com.rambo.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标注 VO 字段：该字段存储的是 OSS objectName（而非可直接访问的 URL），
 * 响应返回前端前由 OssUrlResponseBodyAdvice 统一签名成临时访问 URL。
 * 缓存层只缓存 objectName，与 URL 有效期（30分钟）解耦。
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface OssUrl {
}
