package com.rambo.infrastructure.web;

import org.springdoc.core.models.GroupedOpenApi;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class SwaggerModuleConfig {

    @Bean
    public GroupedOpenApi goodsModule() {
        return GroupedOpenApi.builder()
                .group("商品模块")
                .pathsToMatch("/goods/**")      // 匹配所有 /goods/ 开头的接口
                .build();
    }

    @Bean
    public GroupedOpenApi taskModule() {
        return GroupedOpenApi.builder()
                .group("任务模块")
                .pathsToMatch("/task/**")
                .build();
    }

    @Bean
    public GroupedOpenApi userModule() {
        return GroupedOpenApi.builder()
                .group("用户模块")
                .pathsToMatch("/user/**", "/student/**", "/address/**") // 按实际情况添加
                .build();
    }

    @Bean
    public GroupedOpenApi commonModule() {
        return GroupedOpenApi.builder()
                .group("公共接口")
                .pathsToMatch("/common/**", "/upload/**", "/config/**")
                .build();
    }
}