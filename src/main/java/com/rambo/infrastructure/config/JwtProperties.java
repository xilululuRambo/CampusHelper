package com.rambo.infrastructure.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT 配置属性（随 JwtUtil 下沉至 common 层，@ConfigurationPropertiesScan 默认扫描 com.rambo 全包）
 */
@ConfigurationProperties(prefix = "jwt")
@Data
public class JwtProperties {
    /**
     * 密钥
     */
    private String secret;

    /**
     * 访问令牌过期时间（毫秒）
     */
    private long accessExpiration;

    /**
     * 刷新令牌过期时间（毫秒）
     */
    private long refreshExpiration;
}
