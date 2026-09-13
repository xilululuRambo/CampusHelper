package com.rambo.infrastructure.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
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
        RestClientTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper(esObjectMapper()));
        return new ElasticsearchClient(transport);
    }

    /**
     * ES 专用 ObjectMapper。
     *
     * <p><b>为什么不能复用 Spring 容器里的那一个</b>：{@code JacksonConfig} 为 Web 层注册了
     * Long→String（防雪花 ID 在 JS 端精度丢失）与 {@code yyyy-MM-dd HH:mm:ss} 的 LocalDateTime 格式。
     * 而 {@link EsIndexInitializer} 声明的映射是 {@code long} + {@code strict_date_optional_time||epoch_millis}，
     * 沿用 Web 配置会得到两个坏结果：时间写成空格分隔、不符合 strict 格式，ES 端 date 解析失败
     * （问题只是从「客户端序列化报错」挪到「ES 端 mapping 报错」）；数值字段被写成字符串，
     * 依赖默认 coerce 才能落库，一旦索引关掉 coerce 立刻失效。</p>
     *
     * <p><b>为什么必须显式关掉 WRITE_DATES_AS_TIMESTAMPS</b>：JavaTimeModule 的默认行为是把
     * LocalDateTime 序列化成数组 {@code [2026,9,13,23,29,0]}，ES 同样无法解析。
     * 关闭后输出 ISO-8601（如 {@code 2026-09-13T23:29:00}），与索引声明的 strict_date_optional_time 匹配。</p>
     *
     * <p><b>为什么刻意不做成 {@code @Bean}</b>：一旦注册为 {@code ObjectMapper} 类型的 bean，会命中
     * {@code JacksonAutoConfiguration} 的 {@code @ConditionalOnMissingBean(ObjectMapper)}，
     * 顶掉 Spring Boot 按 {@code JacksonConfig} 定制好的那个 mapper，导致全站 JSON 的
     * Long→String 失效、雪花 ID 以数字形式返回给前端并丢失精度。
     * 包内可见（非 private）仅为便于单测直接断言序列化结果。</p>
     */
    static ObjectMapper esObjectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        return objectMapper;
    }
}