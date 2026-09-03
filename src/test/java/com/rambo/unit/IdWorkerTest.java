package com.rambo.unit;

import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.infrastructure.messaging.IdWorker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 雪花 ID 生成器单元测试（Mockito mock CacheClient 绕过 @PostConstruct，反射注入 workerId）。
 *
 * 覆盖：唯一性（单线程/多线程）、时间单调性、同毫秒并发区分。
 */
class IdWorkerTest {

    private IdWorker idWorker;

    @BeforeEach
    void setUp() throws Exception {
        CacheClient cacheClient = mock(CacheClient.class);
        when(cacheClient.increment("snowflake:worker_id", 1L)).thenReturn(1L);

        idWorker = new IdWorker();
        // 绕过 @PostConstruct：手动注入 workerId（真实容器中由 Redis increment 得到）
        Field f = IdWorker.class.getDeclaredField("workerId");
        f.setAccessible(true);
        f.set(idWorker, 1L);
    }

    @Test
    @DisplayName("单线程连续生成 10000 个 ID：全部唯一")
    void nextId_singleThread_unique() {
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 10000; i++) {
            assertThat(ids.add(idWorker.nextId())).as("第 %d 个 ID 重复", i).isTrue();
        }
        assertThat(ids).hasSize(10000);
    }

    @Test
    @DisplayName("生成 ID 始终为正数且格式合理（时间戳位移后仍为正）")
    void nextId_positive() {
        for (int i = 0; i < 1000; i++) {
            assertThat(idWorker.nextId()).isPositive();
        }
    }

    @Test
    @DisplayName("多线程并发生成 20 万 ID：无重复")
    void nextId_concurrent_unique() throws Exception {
        int threads = 8;
        int perThread = 25000;
        Set<Long> ids = ConcurrentHashMap.newKeySet();
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        ids.add(idWorker.nextId());
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    done.countDown();
                }
            });
        }

        start.countDown();
        assertThat(done.await(30, TimeUnit.SECONDS)).as("并发生成超时").isTrue();
        pool.shutdown();

        assertThat(ids).as("并发下 ID 必须全局唯一（期望 %d 个）", threads * perThread)
                .hasSize(threads * perThread);
    }

    @Test
    @DisplayName("同线程生成的 ID 时间单调（后生成不小于先生成）")
    void nextId_monotonic() {
        long prev = idWorker.nextId();
        for (int i = 0; i < 10000; i++) {
            long cur = idWorker.nextId();
            assertThat(cur).as("ID 应随时间单调不减").isGreaterThan(prev);
            prev = cur;
        }
    }

    @Test
    @DisplayName("不同 workerId 生成的 ID 空间不同（区间隔离）")
    void differentWorkerId_disjoint() throws Exception {
        CacheClient cacheClient = mock(CacheClient.class);
        when(cacheClient.increment("snowflake:worker_id", 1L)).thenReturn(2L);
        IdWorker other = new IdWorker();
        Field f = IdWorker.class.getDeclaredField("workerId");
        f.setAccessible(true);
        f.set(other, 2L);

        AtomicLong mine = new AtomicLong();
        AtomicLong theirs = new AtomicLong();
        for (int i = 0; i < 5000; i++) {
            long a = idWorker.nextId();
            long b = other.nextId();
            assertThat(a).isNotEqualTo(b);
            assertThat(a).isNotEqualTo(theirs.get());
            assertThat(b).isNotEqualTo(mine.get());
            mine.set(a);
            theirs.set(b);
        }
    }
}
