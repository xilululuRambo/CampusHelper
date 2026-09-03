package com.rambo.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import org.apache.http.HttpHost;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Arrays;

@Configuration
public class EsConfig {

    // 连接超时：3 秒
    private static final int CONNECT_TIMEOUT_MS = 3000;
    // 传输超时：5 秒
    private static final int SOCKET_TIMEOUT_MS = 5000;
    // 从连接池获取连接的超时：3 秒
    private static final int CONNECTION_REQUEST_TIMEOUT_MS = 3000;

    @Value("${spring.elasticsearch.uris}")
    private String uris;

    @Bean
    public ElasticsearchClient elasticsearchClient() {
        // 解析uris为HttpHost数组
        String[] uriArray = uris.split(",");
        HttpHost[] hosts = Arrays.stream(uriArray)
                .map(HttpHost::create)
                .toArray(HttpHost[]::new);

// ====================== 新增：超时配置 ======================
        RestClientBuilder builder = RestClient.builder(hosts);
        builder.setRequestConfigCallback(requestConfigBuilder -> {
            requestConfigBuilder
                    .setConnectTimeout(CONNECT_TIMEOUT_MS)      // 连接超时 3s
                    .setSocketTimeout(SOCKET_TIMEOUT_MS)       // 传输超时 5s
                    .setConnectionRequestTimeout(CONNECTION_REQUEST_TIMEOUT_MS);
            return requestConfigBuilder;
        });

        RestClient restClient = builder.build();
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
        return new ElasticsearchClient(transport);
    }
}