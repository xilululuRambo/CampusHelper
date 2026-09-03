package com.rambo.infrastructure.config;

import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean("taskExecutor")
    public Executor taskExecutor() {

        //创建线程池
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        //核心线程大小
        executor.setCorePoolSize(5);
        //最大线程大小
        executor.setMaxPoolSize(10);
        //队列容量
        executor.setQueueCapacity(100);
        //设置线程前缀
        executor.setThreadNamePrefix("Async-");
        //队列满、线程满的时候的拒绝策略
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        //初始化
        executor.initialize();

        // 关键：设置任务装饰器，自动拷贝 MDC
        executor.setTaskDecorator(runnable -> {
            // 1. 在父线程（Web请求线程）中，把 MDC 内容拷出来
            Map<String, String> contextMap = MDC.getCopyOfContextMap();
            return () -> {
                try {
                    // 2. 在子线程（异步线程）执行前，把 MDC 塞进去
                    if (contextMap != null) {
                        MDC.setContextMap(contextMap);
                    }
                    // 3. 执行真正的业务逻辑（比如发 MQ）
                    runnable.run();
                } finally {
                    // 4. 执行完后清空，防止内存泄漏
                    MDC.clear();
                }
            };
        });
        return executor;
    }
}