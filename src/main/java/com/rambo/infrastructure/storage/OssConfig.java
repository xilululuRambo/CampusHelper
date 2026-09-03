package com.rambo.infrastructure.storage;

import com.aliyun.oss.ClientBuilderConfiguration;
import com.aliyun.oss.OSS;
import com.aliyun.oss.OSSClientBuilder;
import jakarta.annotation.Resource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OSS 配置类（包含超时、重试、连接池）
 */
@Configuration
public class OssConfig {

    // 连接超时：3 秒
    private static final int CONNECTION_TIMEOUT_MS = 3000;
    // 数据传输超时：5 秒
    private static final int SOCKET_TIMEOUT_MS = 5000;
    // 最大失败重试次数
    private static final int MAX_ERROR_RETRY = 2;
    // 最大连接数
    private static final int MAX_CONNECTIONS = 20;

    @Resource
    private AliyunOssProperties aliyunOssProperties;

    @Bean
    public OSS ossClient() {
        // 1. 创建 OSS 客户端配置（关键：超时 + 重试）
        ClientBuilderConfiguration clientConfig = new ClientBuilderConfiguration();
        // 连接超时 3秒
        clientConfig.setConnectionTimeout(CONNECTION_TIMEOUT_MS);
        // 数据传输超时 5秒
        clientConfig.setSocketTimeout(SOCKET_TIMEOUT_MS);
        // 最大失败重试次数（避免卡死）
        clientConfig.setMaxErrorRetry(MAX_ERROR_RETRY);
        // 设置最大连接数
        clientConfig.setMaxConnections(MAX_CONNECTIONS);

        // 2. 正确构建 OSS 客户端
        return new OSSClientBuilder().build(
                aliyunOssProperties.getEndpoint(),
                aliyunOssProperties.getAccessKeyId(),
                aliyunOssProperties.getAccessKeySecret(),
                clientConfig
        );
    }
}