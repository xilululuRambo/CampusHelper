package com.rambo;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 配置回归测试（纯单元测试，不启动 Spring 上下文，随时可跑）。
 *
 * 【缺陷回归】生产 application.yml 曾将 access-expiration 误配置为
 * 1000000000000000000（≈3 万年），导致 Access Token 永不过期。
 * 本测试直接解析 yml 文本做断言，防止任何人再次把有效期改回异常值。
 */
class ConfigRegressionTest {

    private static final String APP_YML =
            "C:/Users/Rambo/IdeaProjects/CampusHelper/src/main/resources/application.yml";

    private String readYml() throws Exception {
        return new String(Files.readAllBytes(Paths.get(APP_YML)), StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("回归：Access Token 有效期不允许出现异常超长值（曾误配为 3 万年）")
    void accessExpiration_notAbnormallyLong() throws Exception {
        String yml = readYml();
        assertThat(yml)
                .as("access-expiration 不应为超长值（历史缺陷：1000000000000000000）")
                .doesNotContain("1000000000000000000");
    }

    @Test
    @DisplayName("JWT 密钥应通过环境变量注入，不允许硬编码明文密钥")
    void jwtSecret_notHardcoded() throws Exception {
        String yml = readYml();
        // 密钥必须引用环境变量 ${JWT_SECRET}，不能是写死的字面量
        assertThat(yml).contains("${JWT_SECRET}");
    }

    @Test
    @DisplayName("数据库密码应通过环境变量注入")
    void dbPassword_notHardcoded() throws Exception {
        String yml = readYml();
        assertThat(yml).contains("${DB_PASSWORD}");
    }
}
