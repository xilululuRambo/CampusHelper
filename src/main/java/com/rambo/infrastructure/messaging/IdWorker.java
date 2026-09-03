package com.rambo.infrastructure.messaging;

import com.rambo.infrastructure.cache.CacheClient;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.springframework.stereotype.Component;

@Component
public class IdWorker {
    @Resource
    private CacheClient cacheClient;
    // 机器编号，集群每台机器设不同值，防止ID重复
    private static final String WORKER_ID_KEY = "snowflake:worker_id";
    private Long workerId;

    @PostConstruct
    public void initWorkerId() {
        Long id = cacheClient.increment(WORKER_ID_KEY, 1);
        // 判空兜底 + 取模限制在10位范围内，防止溢出侵占时间戳位
        workerId = (id != null ? id : 0L) % 1024;
    }
    // 同一毫秒内自增序号，毫秒内多条消息靠它区分
    private long sequence = 0;
    // 记录上一次生成ID的时间
    private long lastTime = -1;
    // 算法起始基准时间：2026-01-01
    private static final long START_TIME = 1767196800000L;
    // 回拨容忍阈值（毫秒）
    private static final long MAX_BACKWARD_MS = 5;

    // 同步方法，保证多线程生成ID不重复
    public synchronized long nextId() {
        long now = System.currentTimeMillis();
        // 时间回拨处理：小幅等待，大幅沿用上次时间
        if (now < lastTime) {
            long offset = lastTime - now;
            if (offset <= MAX_BACKWARD_MS) {
                try {
                    Thread.sleep(offset + 1);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                now = System.currentTimeMillis();
            }
            if (now < lastTime) {
                now = lastTime;
            }
        }
        // 同一毫秒内多次生成ID
        if (now == lastTime) {
            // 序号自增，最大4095
            sequence = (sequence + 1) & 4095;
            // 序号用尽，等待下一毫秒再生成
            if (sequence == 0) now = waitNextTime(lastTime);
        } else {
            // 新毫秒，序号重置为0
            sequence = 0;
        }
        // 更新上次生成时间为当前时间
        lastTime = now;
        // 拼接时间、机器、序号，算出最终唯一ID
        return ((now - START_TIME) << 22) | (workerId << 12) | sequence;
    }

    // 循环等待下一毫秒，sleep 避免 CPU 空转
    private long waitNextTime(long oldTime) {
        long now = System.currentTimeMillis();
        while (now <= oldTime) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            now = System.currentTimeMillis();
        }
        return now;
    }
}
