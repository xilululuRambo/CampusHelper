package com.rambo;

import com.rambo.helper.AuthUser;
import com.rambo.helper.TestAuthHelper;
import com.rambo.infrastructure.storage.AliyunOssUtil;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * 性能压测（@Tag("perf")，常规 mvn test 不执行，用 mvn test -Dtest=PerfApiTest 单独运行）。
 *
 * 设计说明：
 *  - 真实全链路（真实 HTTP + 真实 MySQL/Redis/Mongo/RabbitMQ），仅 mock OSS 避免真实上传阿里云
 *  - 三个场景：读接口（无锁）、写接口-同一商品（锁串行化）、写接口-不同商品（无锁竞争）
 *  - 压测客户端与应用同 JVM（RANDOM_PORT 内嵌容器），数值为相对参考；
 *    真实环境应压测机与服务器分离（见方案文档 JMeter 部分）
 */
@Tag("perf")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class PerfApiTest {

    @LocalServerPort
    private int port;

    @Resource
    private TestRestTemplate rest;

    @Resource
    private TestAuthHelper authHelper;

    /** 仅 mock OSS：避免压测真实上传/删除阿里云对象 */
    @MockBean
    private AliyunOssUtil aliyunOssUtil;

    @BeforeEach
    void stubOss() {
        when(aliyunOssUtil.uploadFilesAndGetNames(any())).thenReturn(List.of("perf-1.jpg", "perf-2.jpg"));
        when(aliyunOssUtil.upload(any())).thenReturn("perf-upload.jpg");
        when(aliyunOssUtil.getUrl(any())).thenReturn("https://mock.oss.example.com/perf.jpg");
    }

    private String baseUrl() {
        return "http://localhost:" + port + "/api";
    }

    private HttpHeaders headers(AuthUser user) {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        h.setBearerAuth(user.getAccessToken());
        return h;
    }

    private ResponseEntity<Map> get(String path, AuthUser user) {
        return rest.exchange(baseUrl() + path, HttpMethod.GET, new HttpEntity<>(headers(user)), Map.class);
    }

    private ResponseEntity<Map> post(String path, Object body, AuthUser user) {
        return rest.exchange(baseUrl() + path, HttpMethod.POST, new HttpEntity<>(body, headers(user)), Map.class);
    }

    /** 发布商品，返回 goodsId */
    private Long publishGoods(AuthUser seller) {
        Map<String, Object> form = new HashMap<>();
        form.put("title", "压测商品-" + System.nanoTime());
        form.put("description", "压测数据");
        form.put("categoryId", "1");
        form.put("price", "100");
        form.put("images", new ByteArrayResource("img".getBytes()) {
            @Override
            public String getFilename() {
                return "p.jpg";
            }
        });
        HttpHeaders h = headers(seller);
        h.setContentType(MediaType.MULTIPART_FORM_DATA);
        org.springframework.util.MultiValueMap<String, Object> body = new org.springframework.util.LinkedMultiValueMap<>();
        form.forEach(body::add);
        rest.exchange(baseUrl() + "/goods", HttpMethod.POST, new HttpEntity<>(body, h), Map.class);
        Map<?, ?> my = get("/goods/my?pageNum=1&pageSize=1", seller).getBody();
        List<Map<String, Object>> records = (List<Map<String, Object>>) ((Map<?, ?>) my.get("data")).get("records");
        return Long.parseLong(String.valueOf(records.get(0).get("id")));
    }

    // ==================== 压测场景 ====================

    @Test
    @DisplayName("读接口：GET /goods/list 无关键词分页（无锁吞吐）")
    void perf_readGoodsList() throws Exception {
        AuthUser user = authHelper.registerAndLogin(); // 读接口仅需登录
        int threads = 8, perThread = 100;
        PerfResult r = runConcurrent(threads, perThread, () -> {
            ResponseEntity<Map> resp = get("/goods/list?pageNum=1&pageSize=10", user);
            return resp.getBody() != null && Integer.valueOf(200).equals(resp.getBody().get("code"));
        });
        print("读接口 GET /goods/list（8 线程 x 100）", r);
    }

    @Test
    @DisplayName("写接口：10 买家并发购买同一商品（分布式锁串行化）")
    void perf_buySameGoods() throws Exception {
        AuthUser seller = authHelper.registerAndLoginWithAuth();
        Long goodsId = publishGoods(seller);

        int buyers = 10;
        List<AuthUser> buyerList = new ArrayList<>();
        for (int i = 0; i < buyers; i++) {
            buyerList.add(authHelper.registerAndLoginWithAuth());
        }
        PerfResult r = runConcurrent(buyers, 1, new ConcurrentTask() {
            private final AtomicInteger idx = new AtomicInteger();

            @Override
            public boolean call() {
                AuthUser b = buyerList.get(idx.getAndIncrement());
                ResponseEntity<Map> resp = post("/goods/buy/" + goodsId, null, b);
                return resp.getBody() != null && Integer.valueOf(200).equals(resp.getBody().get("code"));
            }
        });
        print("写接口 同一商品 POST /goods/buy/" + goodsId + "（10 买家并发）", r);
    }

    @Test
    @DisplayName("写接口：10 买家各买各的商品（无锁竞争吞吐）")
    void perf_buyDifferentGoods() throws Exception {
        AuthUser seller = authHelper.registerAndLoginWithAuth();
        int goodsCount = 10;
        List<Long> goodsIds = new ArrayList<>();
        for (int i = 0; i < goodsCount; i++) {
            goodsIds.add(publishGoods(seller));
        }
        List<AuthUser> buyerList = new ArrayList<>();
        for (int i = 0; i < goodsCount; i++) {
            buyerList.add(authHelper.registerAndLoginWithAuth());
        }
        PerfResult r = runConcurrent(goodsCount, 1, new ConcurrentTask() {
            private final AtomicInteger idx = new AtomicInteger();

            @Override
            public boolean call() {
                int i = idx.getAndIncrement();
                AuthUser b = buyerList.get(i);
                ResponseEntity<Map> resp = post("/goods/buy/" + goodsIds.get(i), null, b);
                return resp.getBody() != null && Integer.valueOf(200).equals(resp.getBody().get("code"));
            }
        });
        print("写接口 不同商品 POST /goods/buy/{id}（10 商品 x 10 买家）", r);
    }

    // ==================== 并发执行与统计 ====================

    interface ConcurrentTask {
        boolean call();
    }

    static class PerfResult {
        long qps;
        double avgMs;
        long tp50, tp95, tp99, maxMs;
        int success, fail;
    }

    private PerfResult runConcurrent(int threads, int perThread, ConcurrentTask task) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<long[]> allRts = Collections.synchronizedList(new ArrayList<>());
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger fail = new AtomicInteger();
        AtomicLong startMs = new AtomicLong();

        for (int t = 0; t < threads; t++) {
            pool.submit(() -> {
                long[] rts = new long[perThread];
                try {
                    start.await();
                    for (int i = 0; i < perThread; i++) {
                        long s = System.nanoTime();
                        try {
                            if (task.call()) {
                                ok.incrementAndGet();
                            } else {
                                fail.incrementAndGet();
                            }
                        } catch (Exception e) {
                            fail.incrementAndGet();
                        }
                        rts[i] = (System.nanoTime() - s) / 1_000_000;
                    }
                    allRts.add(rts);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        startMs.set(System.nanoTime());
        start.countDown();
        pool.shutdown();
        pool.awaitTermination(120, TimeUnit.SECONDS);

        long totalMs = (System.nanoTime() - startMs.get()) / 1_000_000;
        long total = ok.get() + fail.get();
        List<Long> merged = new ArrayList<>();
        for (long[] rts : allRts) {
            for (long rt : rts) {
                merged.add(rt);
            }
        }
        Collections.sort(merged);

        PerfResult r = new PerfResult();
        r.qps = totalMs > 0 ? Math.round(total * 1000.0 / totalMs) : 0;
        r.success = ok.get();
        r.fail = fail.get();
        if (!merged.isEmpty()) {
            r.avgMs = Math.round(merged.stream().mapToLong(Long::longValue).average().orElse(0) * 10) / 10.0;
            r.tp50 = percentile(merged, 0.50);
            r.tp95 = percentile(merged, 0.95);
            r.tp99 = percentile(merged, 0.99);
            r.maxMs = merged.get(merged.size() - 1);
        }
        return r;
    }

    private long percentile(List<Long> sorted, double p) {
        int idx = (int) Math.ceil(sorted.size() * p) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, idx)));
    }

    private void print(String scene, PerfResult r) {
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== ").append(scene).append(" ==========\n");
        sb.append("总请求: ").append(r.success + r.fail)
                .append("  成功: ").append(r.success)
                .append("  失败: ").append(r.fail)
                .append("  成功率: ").append(r.success + r.fail == 0 ? "-"
                        : Math.round(r.success * 1000.0 / (r.success + r.fail)) / 10.0).append("%\n");
        sb.append("QPS: ").append(r.qps).append("\n");
        sb.append("RT  avg: ").append(r.avgMs).append("ms  TP50: ").append(r.tp50)
                .append("ms  TP95: ").append(r.tp95).append("ms  TP99: ").append(r.tp99)
                .append("ms  max: ").append(r.maxMs).append("ms\n");
        System.out.println(sb);
    }
}
