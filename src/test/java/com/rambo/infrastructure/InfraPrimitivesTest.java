package com.rambo.infrastructure;

import com.rambo.BaseApiTest;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.infrastructure.cache.LockClient;
import com.rambo.infrastructure.database.TransactionUtils;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 基础设施原语测试：{@link LockClient} / {@link CacheClient} / {@link TransactionUtils}。
 *
 * <p><b>为什么这三个组件值得单独测：</b>它们是全项目所有业务链路的地基——
 * 分布式锁决定并发正确性、缓存门面的 field 级 TTL 决定多设备会话与节流行为、
 * 事务钩子决定「外部副作用是否会在事务回滚后错误执行」。这三者的缺陷不会让任何
 * 单个接口测红，却会在生产上表现为「偶发重复下单 / 会话永不过期 / 假通知」，
 * 排查成本远高于在此处钉死行为。</p>
 *
 * <p>全部使用真实 Redis（db1），不 mock——这几个类的价值恰恰在于「屏蔽客户端差异」，
 * mock 掉客户端后测的就只剩一层转发，无法暴露序列化不兼容、TTL 语义偏差等问题。</p>
 */
class InfraPrimitivesTest extends BaseApiTest {

    @Resource
    private LockClient lockClient;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private PlatformTransactionManager transactionManager;

    /** 每个用例独占的 key 前缀，避免与业务 key 相互干扰 */
    private String key;

    @BeforeEach
    void prepareKey() {
        // 用纳秒保证并发跑用例时也不撞（用例本身串行，但历史残留 key 可能未清干净）
        key = "test:infra:" + System.nanoTime() + ":";
    }

    // ==================== LockClient ====================

    @Test
    @DisplayName("tryLock 看门狗模式：同一线程可重入，第二把锁直接成功")
    void tryLock_withoutHoldTime_isReentrant() {
        String k = key + "reentrant";

        assertThat(lockClient.tryLock(k, 0, TimeUnit.SECONDS))
                .as("首次加锁必须成功")
                .isTrue();
        // Redisson RLock 是重入锁：同一线程（同一冲突 key 体系）再次 tryLock 会直接拿到
        assertThat(lockClient.tryLock(k, 0, TimeUnit.SECONDS))
                .as("同一线程重入应直接成功——Redisson RLock 的重入语义依赖 Redis Hash 里的持有计数")
                .isTrue();

        // 重入两把就必须解锁两次：只解一次锁仍被本线程持有，
        // 这是「tryLock 写在循环里」最容易踩的坑，也解释了 unlock 为何必须与 tryLock 次数配对
        lockClient.unlock(k);
        assertThat(lockClient.tryLock(k, 0, TimeUnit.SECONDS))
                .as("只解锁一次后锁仍被本线程持有（重入计数未归零），重入仍应成功")
                .isTrue();

        lockClient.unlock(k);
        lockClient.unlock(k);
        assertThat(lockClient.tryLock(k, 0, TimeUnit.SECONDS))
                .as("计数归零后重新加锁仍是本线程，应成功")
                .isTrue();
        lockClient.unlock(k);
    }

    @Test
    @DisplayName("tryLock 等待 0 秒：锁被他人持有时立即失败，不阻塞")
    void tryLock_heldByOther_returnsFalseImmediately() throws Exception {
        String k = key + "contended";

        assertThat(lockClient.tryLock(k, 0, TimeUnit.SECONDS)).isTrue();

        AtomicReference<Boolean> otherThreadResult = new AtomicReference<>();
        AtomicReference<Long> waitedMs = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);

        Thread other = new Thread(() -> {
            long start = System.nanoTime();
            // waitTime=0：抢不到立刻返回，这正是 Job 侧「抢不到就跳过本次」的实现基础
            otherThreadResult.set(lockClient.tryLock(k, 0, TimeUnit.SECONDS));
            waitedMs.set((System.nanoTime() - start) / 1_000_000);
            done.countDown();
        });
        other.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).as("抢锁线程应在超时内结束").isTrue();
        other.join();

        assertThat(otherThreadResult.get())
                .as("锁已被主线程持有，另一线程 waitTime=0 必须立刻失败——"
                        + "Job 靠这个返回值决定「跳过本轮」而不是排队等待")
                .isFalse();
        assertThat(waitedMs.get())
                .as("waitTime=0 不应产生等待，实际等待 %d ms", waitedMs.get())
                .isLessThan(1_000L);

        lockClient.unlock(k);
    }

    @Test
    @DisplayName("tryLock 指定 holdTime：超过持有时间后被自动释放（防死锁）")
    void tryLock_withHoldTime_expiresAutomatically() throws Exception {
        String k = key + "hold";

        // holdTime 200ms：锁到期强制释放，即使持锁线程不主动 unlock 也不会永久占住
        assertThat(lockClient.tryLock(k, 0, 200, TimeUnit.MILLISECONDS)).isTrue();

        AtomicReference<Boolean> afterExpiry = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Thread other = new Thread(() -> {
            // 等锁过期后再抢
            try {
                Thread.sleep(600);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            afterExpiry.set(lockClient.tryLock(k, 0, TimeUnit.SECONDS));
            done.countDown();
        });
        other.start();
        assertThat(done.await(5, TimeUnit.SECONDS)).isTrue();
        other.join();

        assertThat(afterExpiry.get())
                .as("holdTime 到期后锁必须被 Redisson 自动释放（走的是 leaseTime 而非看门狗续期），"
                        + "否则持锁线程崩溃会永久锁死临界区")
                .isTrue();

        // 收尾：这把锁由另一线程持有
        new Thread(() -> lockClient.unlock(k)).start();
    }

    @Test
    @DisplayName("unlock 幂等且不误删他人锁：未持有时调用不抛异常")
    void unlock_whenNotHeld_isNoOp() {
        String k = key + "notheld";

        // 锁从未被本线程持有：isHeldByCurrentThread() 为 false，unlock 直接跳过
        lockClient.unlock(k);

        // 换一个线程持锁，主线程调用 unlock 必须不生效（否则会破坏他人的临界区）
        Thread holder = new Thread(() -> lockClient.tryLock(k, 0, TimeUnit.SECONDS));
        holder.start();
        try {
            holder.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        lockClient.unlock(k);

        // 关键断言：持有者线程仍能重入，说明主线程的 unlock 没有把锁释放掉
        AtomicReference<Boolean> holderStillHolds = new AtomicReference<>();
        Thread checker = new Thread(() -> holderStillHolds.set(lockClient.tryLock(k, 0, TimeUnit.SECONDS)));
        // 注意：Redisson 锁的持有者身份是「线程」，必须让持锁的那条线程自己验证，
        // 这里借助 isHeldByCurrentThread 无法跨线程断言，故换个角度验证：
        // 主线程 unlock 后，另一个新线程仍抢不到 → 锁确实没被释放
        AtomicReference<Boolean> newThreadCanLock = new AtomicReference<>();
        Thread outsider = new Thread(() -> newThreadCanLock.set(lockClient.tryLock(k, 0, TimeUnit.SECONDS)));
        outsider.start();
        checker.start();
        try {
            outsider.join(3000);
            checker.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThat(newThreadCanLock.get())
                .as("主线程未持有锁却调用 unlock，不得释放他人的锁——"
                        + "否则 A 线程的临界区会被 B 线程提前打开")
                .isFalse();

        // 收尾：由真正持有的线程释放
        Thread releaser = new Thread(() -> lockClient.unlock(k));
        releaser.start();
        try {
            releaser.join(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Test
    @DisplayName("tryLock 并发互斥：N 个线程同时抢，任一时刻只有一个进入临界区")
    void tryLock_concurrent_onlyOneEntersCriticalSection() throws Exception {
        String k = key + "mutex";
        int threads = 8;
        AtomicInteger concurrent = new AtomicInteger();
        AtomicInteger maxConcurrent = new AtomicInteger();
        AtomicInteger acquired = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    // 等 2 秒也要抢到，保证每个线程都真正进过临界区（否则用例退化为「都没抢到」）
                    if (lockClient.tryLock(k, 2, TimeUnit.SECONDS)) {
                        acquired.incrementAndGet();
                        int now = concurrent.incrementAndGet();
                        maxConcurrent.accumulateAndGet(now, Math::max);
                        Thread.sleep(50);
                        concurrent.decrementAndGet();
                        lockClient.unlock(k);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(finish.await(30, TimeUnit.SECONDS)).as("所有抢锁线程应在超时内结束").isTrue();
        pool.shutdownNow();

        assertThat(acquired.get())
                .as("锁必须可被逐个获取，否则用例退化成「锁压根不可用」")
                .isEqualTo(threads);
        assertThat(maxConcurrent.get())
                .as("任一时刻进入临界区的线程数必须为 1——这是分布式锁存在的唯一理由")
                .isEqualTo(1);
    }

    // ==================== CacheClient ====================

    @Test
    @DisplayName("getRemainTtl：区分「不存在(-2)」「无过期(-1)」「有 TTL(>0)」三种语义")
    void getRemainTtl_distinguishesThreeStates() {
        String k = key + "ttl";

        assertThat(cacheClient.getRemainTtl(k))
                .as("key 不存在必须返回 -2（Redis TTL 约定），"
                        + "调用方靠这个值区分「没数据」与「数据永久」")
                .isEqualTo(-2L);

        cacheClient.set(k, "v");
        assertThat(cacheClient.getRemainTtl(k))
                .as("写入但未设过期应返回 -1（永久）")
                .isEqualTo(-1L);

        cacheClient.set(k, "v", 10, TimeUnit.MINUTES);
        long ttl = cacheClient.getRemainTtl(k);
        assertThat(ttl)
                .as("设置了 10 分钟 TTL 的 key 剩余时间应在 (0, 10min] 区间，实际 %d ms", ttl)
                .isGreaterThan(0L)
                .isLessThanOrEqualTo(TimeUnit.MINUTES.toMillis(10));

        cacheClient.delete(k);
    }

    @Test
    @DisplayName("rename 为原子操作：源 key 消失、目标 key 就位（排行榜归档基础）")
    void rename_isAtomicMove() {
        String src = key + "rank:2026-08";
        String dst = key + "rank:archive";

        cacheClient.zIncrementScore(src, "u1", 5);
        cacheClient.zIncrementScore(src, "u2", 3);

        cacheClient.rename(src, dst);

        assertThat(cacheClient.hasKey(src))
                .as("RENAME 后源 key 必须消失——排行榜归档正是靠这一步让「当月榜」自动空缺，"
                        + "新搜索直接写入新 key，无需额外清理")
                .isFalse();
        List<CacheClient.ZSetEntry> moved = cacheClient.zReverseRangeWithScores(dst, 0, -1);
        assertThat(moved).hasSize(2);
        assertThat(moved.get(0).value())
                .as("RENAME 保留 ZSet 结构与分数，排名顺序不能被打乱")
                .isEqualTo("u1");
        assertThat(moved.get(0).score()).isEqualTo(5D);
        assertThat(moved.get(1).value()).isEqualTo("u2");

        cacheClient.delete(dst);
    }

    @Test
    @DisplayName("zReverseRangeWithScores 必须保持分数倒序（顺序错乱=排行榜看不出名次）")
    void zReverseRange_keepsDescendingOrder() {
        String k = key + "zset";
        // 故意乱序写入，验证读取时按分数倒序而非写入序
        cacheClient.zIncrementScore(k, "low", 1);
        cacheClient.zIncrementScore(k, "high", 100);
        cacheClient.zIncrementScore(k, "mid", 50);

        List<CacheClient.ZSetEntry> all = cacheClient.zReverseRangeWithScores(k, 0, -1);
        assertThat(all).extracting(CacheClient.ZSetEntry::value)
                .as("必须按分数从高到低返回——实现里刻意用有序 List 收集 TypedTuple，"
                        + "若误用 Collectors.toSet() 会退化成 HashSet，Top 顺序随机错乱")
                .containsExactly("high", "mid", "low");

        List<CacheClient.ZSetEntry> top2 = cacheClient.zReverseRangeWithScores(k, 0, 1);
        assertThat(top2).extracting(CacheClient.ZSetEntry::value)
                .as("区间 [0,1] 应取前两名，与归档 Job 的 TOP_RANK_END_INDEX 语义一致")
                .containsExactly("high", "mid");

        assertThat(cacheClient.zReverseRangeWithScores(key + "nonexistent", 0, -1))
                .as("key 不存在时返回空列表而非 null，调用方无需判空")
                .isEmpty();

        cacheClient.delete(k);
    }

    @Test
    @DisplayName("scanKeys 按 pattern 匹配，且不误伤同前缀其他 key")
    void scanKeys_matchesPatternOnly() {
        String prefix = key + "hot:";
        cacheClient.zIncrementScore(prefix + "keywords", "a", 1);
        cacheClient.zIncrementScore(prefix + "keywords:archive:2026-09-12", "b", 2);
        cacheClient.zIncrementScore(prefix + "keywords:archive:2026-09-13", "c", 3);
        cacheClient.set(key + "unrelated", "x");

        Set<String> archives = cacheClient.scanKeys(prefix + "keywords:archive:*");
        assertThat(archives)
                .as("应精确命中两个归档 key——热搜归档 Job 靠它收编孤儿 key，"
                        + "模式写松会把当天 key 一起收编导致数据错乱")
                .hasSize(2)
                .allMatch(s -> s.startsWith(prefix + "keywords:archive:"));

        assertThat(cacheClient.scanKeys(prefix + "keywords"))
                .as("SCAN 的 match 是全匹配而非前缀匹配，不带 * 时只命中完全相等的 key")
                .containsExactly(prefix + "keywords");

        cacheClient.delete(prefix + "keywords");
        cacheClient.delete(prefix + "keywords:archive:2026-09-12");
        cacheClient.delete(prefix + "keywords:archive:2026-09-13");
        cacheClient.delete(key + "unrelated");
    }

    @Test
    @DisplayName("mapExpireEntry 只续期不重写值（消除读改写竞态）")
    void mapExpireEntry_refreshesTtlWithoutTouchingValue() {
        String k = key + "session";
        cacheClient.mapPut(k, "device-1", "authStatus:status-A", 3, TimeUnit.SECONDS);

        String before = cacheClient.mapGet(k, "device-1");
        assertThat(before).isEqualTo("authStatus:status-A");

        assertThat(cacheClient.mapExpireEntry(k, "device-1", 10, TimeUnit.MINUTES))
                .as("字段存在时续期必须返回 true")
                .isTrue();

        assertThat(cacheClient.mapGet(k, "device-1"))
                .as("续期不得改写 value——这正是 expireEntry 相对「读 + 写回」方案的价值："
                        + "单条 Lua 只动超时记账，没有「读到旧值再写回」的竞态窗口")
                .isEqualTo("authStatus:status-A");

        long ttl = cacheClient.mapRemainTtl(k, "device-1");
        assertThat(ttl)
                .as("续期后剩余 TTL 应接近 10 分钟而非原来的 3 秒，实际 %d ms", ttl)
                .isGreaterThan(TimeUnit.MINUTES.toMillis(9));

        cacheClient.mapRemove(k, "device-1");
    }

    @Test
    @DisplayName("mapExpireEntry 对不存在字段返回 false：不复活已结束会话")
    void mapExpireEntry_absentField_returnsFalse() {
        String k = key + "session2";

        assertThat(cacheClient.mapExpireEntry(k, "ghost", 5, TimeUnit.MINUTES))
                .as("字段不存在时续期必须返回 false 而不是凭空创建——"
                        + "登录续期若用「续期成功与否」决定后续动作，这里返回 true 会让已登出会话被复活")
                .isFalse();

        assertThat(cacheClient.mapGet(k, "ghost")).isNull();
    }

    @Test
    @DisplayName("mapExpireEntry 拒绝非正数 TTL：0/负数会被 Redisson 解释为「永不过期」")
    void mapExpireEntry_rejectsNonPositiveTtl() {
        String k = key + "session3";
        cacheClient.mapPut(k, "f", "v", 1, TimeUnit.MINUTES);

        assertThatThrownBy(() -> cacheClient.mapExpireEntry(k, "f", 0, TimeUnit.MINUTES))
                .as("ttl=0 必须显式拒绝——Redisson expireEntry 把 0/负数当成「移除 TTL」，"
                        + "会静默把会话改成永不过期，且调用方从返回值看不出异常")
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ttl 必须为正数");

        assertThatThrownBy(() -> cacheClient.mapExpireEntry(k, "f", -1, TimeUnit.SECONDS))
                .isInstanceOf(IllegalArgumentException.class);

        cacheClient.mapRemove(k, "f");
    }

    @Test
    @DisplayName("map 字段级 TTL 独立：一个字段过期不影响另一个（多设备会话）")
    void mapFieldTtl_isIndependentPerField() throws Exception {
        String k = key + "multi-device";
        cacheClient.mapPut(k, "device-short", "tokenA", 300, TimeUnit.MILLISECONDS);
        cacheClient.mapPut(k, "device-long", "tokenB", 10, TimeUnit.MINUTES);

        Thread.sleep(700);

        assertThat(cacheClient.mapGet(k, "device-short"))
                .as("短 TTL 字段应已过期——多设备登录的核心诉求是「各设备各自续期、"
                        + "一台长期不用就单独失效」，不能互相株连")
                .isNull();
        assertThat(cacheClient.mapGet(k, "device-long"))
                .as("长 TTL 字段不得被邻近字段的过期带走")
                .isEqualTo("tokenB");
        assertThat(cacheClient.mapRemainTtl(k, "device-short"))
                .as("已过期字段的剩余 TTL 应为 -2，供调用方与「未设置 TTL」区分开")
                .isEqualTo(-2L);

        cacheClient.mapRemove(k, "device-long");
    }

    @Test
    @DisplayName("mapRemove 只删指定字段，不清空整个 key")
    void mapRemove_deletesSingleFieldOnly() {
        String k = key + "rm";
        cacheClient.mapPut(k, "a", "1", 5, TimeUnit.MINUTES);
        cacheClient.mapPut(k, "b", "2", 5, TimeUnit.MINUTES);

        cacheClient.mapRemove(k, "a");

        assertThat(cacheClient.mapGet(k, "a")).isNull();
        assertThat(cacheClient.mapGet(k, "b"))
                .as("单字段删除不得波及同 key 其他字段——退出登录只该清掉当前设备")
                .isEqualTo("2");

        cacheClient.mapRemove(k, "b");
    }

    @Test
    @DisplayName("setIfAbsent 原子性：并发 N 个线程只有 1 个拿到（冷却/防重基础）")
    void setIfAbsent_onlyOneWinnerUnderConcurrency() throws Exception {
        String k = key + "lock-flag";
        int threads = 8;
        AtomicInteger winners = new AtomicInteger();
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(threads);
        ExecutorService pool = Executors.newFixedThreadPool(threads);

        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                try {
                    startGate.await();
                    Boolean ok = cacheClient.setIfAbsent(k, "winner", 1, TimeUnit.MINUTES);
                    if (Boolean.TRUE.equals(ok)) {
                        winners.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    finish.countDown();
                }
            });
        }
        startGate.countDown();
        assertThat(finish.await(10, TimeUnit.SECONDS)).isTrue();
        pool.shutdownNow();

        assertThat(winners.get())
                .as("setIfAbsent 必须原子：并发下只允许一个成功——"
                        + "验证码 60 秒冷却、防重复提交令牌都建立在这个语义上")
                .isEqualTo(1);

        cacheClient.delete(k);
    }

    // ==================== TransactionUtils ====================

    @Test
    @DisplayName("afterCommit 在事务提交后执行（而非方法返回时）")
    void afterCommit_runsAfterCommit() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicInteger ranInside = new AtomicInteger();
        AtomicInteger ran = new AtomicInteger();

        tx.executeWithoutResult(status -> {
            TransactionUtils.afterCommit(() -> {
                ran.incrementAndGet();
                // 回调执行时事务已提交：DB 侧变更对本连接已可见且不可撤销
                ranInside.incrementAndGet();
            });
            assertThat(ran.get())
                    .as("注册 afterCommit 后不得在事务内立即执行——否则「提交前的副作用」"
                            + "（发 MQ、写缓存）会在回滚时变成假数据")
                    .isZero();
        });

        assertThat(ran.get()).as("事务提交后回调必须执行一次").isEqualTo(1);
        assertThat(ranInside.get())
                .as("回调必须发生在事务边界内执行完毕之后，而非提交前")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("afterCommit 阶段同步上下文仍存活（Spring 源码级事实，影响回调内的实现选择）")
    void afterCommit_synchronizationStillActiveInsideCallback() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicReference<Boolean> syncActiveInsideCallback = new AtomicReference<>();
        AtomicReference<Boolean> syncActiveInsideAfterCompletion = new AtomicReference<>();

        tx.executeWithoutResult(status -> {
            TransactionUtils.afterCommit(() -> syncActiveInsideCallback.set(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .isSynchronizationActive()));
            TransactionUtils.afterTransaction(() -> syncActiveInsideAfterCompletion.set(
                    org.springframework.transaction.support.TransactionSynchronizationManager
                            .isSynchronizationActive()));
        });

        // 反编译 spring-tx 6.1.6 得到的事实（AbstractPlatformTransactionManager#triggerAfterCommit
        // 只调 TransactionSynchronizationUtils.triggerAfterCommit()，不清同步上下文；
        // 而 triggerAfterCompletion 会先 clearSynchronization() 再回调）：
        //   → afterCommit 阶段 isSynchronizationActive() 仍为 true，afterCompletion 阶段才变 false
        assertThat(syncActiveInsideCallback.get())
                .as("afterCommit 回调内同步上下文仍处于激活状态——这直接决定实现约束："
                        + "回调里再调 TransactionUtils.afterCommit 会注册到一个永不触发的同步器上"
                        + "（afterCommit 已过、afterCompletion 尚未跑），副作用被静默丢弃")
                .isTrue();
        assertThat(syncActiveInsideAfterCompletion.get())
                .as("afterCompletion 回调内同步上下文已被 clearSynchronization() 清理")
                .isFalse();
    }

    @Test
    @DisplayName("afterCommit 在事务回滚时不执行（假通知的根因防线）")
    void afterCommit_skippedOnRollback() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicInteger ran = new AtomicInteger();

        tx.executeWithoutResult(status -> {
            TransactionUtils.afterCommit(ran::incrementAndGet);
            status.setRollbackOnly();
        });

        assertThat(ran.get())
                .as("事务回滚后 afterCommit 必须一次都不执行——这是「假通知/假会话」"
                        + "最直接的防线，一旦这里执行了，用户会收到一条对应不存在业务的通知")
                .isZero();
    }

    @Test
    @DisplayName("afterCommit 无事务上下文时立即执行（与直接调用等价）")
    void afterCommit_withoutTransaction_runsImmediately() {
        AtomicInteger ran = new AtomicInteger();
        TransactionUtils.afterCommit(ran::incrementAndGet);

        assertThat(ran.get())
                .as("无事务时立即执行，调用方无需感知是否处于事务中")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("afterTransaction 提交与回滚都执行（解锁场景）")
    void afterTransaction_runsOnBothOutcomes() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        AtomicInteger onCommit = new AtomicInteger();
        tx.executeWithoutResult(status -> TransactionUtils.afterTransaction(onCommit::incrementAndGet));
        assertThat(onCommit.get()).as("事务提交后应执行").isEqualTo(1);

        AtomicInteger onRollback = new AtomicInteger();
        tx.executeWithoutResult(status -> {
            TransactionUtils.afterTransaction(onRollback::incrementAndGet);
            status.setRollbackOnly();
        });
        assertThat(onRollback.get())
                .as("事务回滚后也必须执行——分布式锁的解锁必须发生在「事务彻底结束」之后，"
                        + "与成败无关；若只在提交时解锁，回滚路径会漏解锁")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("onRollback 仅在回滚时执行，提交时不动")
    void onRollback_onlyOnRollback() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        AtomicInteger onCommit = new AtomicInteger();
        tx.executeWithoutResult(status -> TransactionUtils.onRollback(onCommit::incrementAndGet));
        assertThat(onCommit.get())
                .as("事务提交时补偿动作（删 OSS 孤儿文件）不该执行，否则会把正常使用的文件删掉")
                .isZero();

        AtomicInteger onRollback = new AtomicInteger();
        tx.executeWithoutResult(status -> {
            TransactionUtils.onRollback(onRollback::incrementAndGet);
            status.setRollbackOnly();
        });
        assertThat(onRollback.get())
                .as("事务回滚时必须执行补偿：事务内已上传的 OSS 文件此时成了孤儿，"
                        + "不删就永久泄漏")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("onRollback 无事务上下文时不执行（并告警）")
    void onRollback_withoutTransaction_isSkipped() {
        AtomicInteger ran = new AtomicInteger();
        TransactionUtils.onRollback(ran::incrementAndGet);

        assertThat(ran.get())
                .as("无事务时没有回滚语义，补偿动作不能执行——若此处执行，"
                        + "非事务路径下的正常文件会被当成孤儿删除")
                .isZero();
    }

    @Test
    @DisplayName("afterCompletion 把最终状态告知回调：提交=0，回滚=1")
    void afterCompletion_passesFinalStatus() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        AtomicReference<Integer> commitStatus = new AtomicReference<>();
        tx.executeWithoutResult(status ->
                TransactionUtils.afterCompletion(commitStatus::set));
        assertThat(commitStatus.get())
                .as("提交状态应为 STATUS_COMMITTED(0)，审计日志靠它决定记成功还是失败")
                .isEqualTo(TransactionSynchronization.STATUS_COMMITTED);

        AtomicReference<Integer> rollbackStatus = new AtomicReference<>();
        tx.executeWithoutResult(status -> {
            TransactionUtils.afterCompletion(rollbackStatus::set);
            status.setRollbackOnly();
        });
        assertThat(rollbackStatus.get())
                .as("回滚状态应为 STATUS_ROLLED_BACK(1)——这是 P1-6「审计随事务结果改写」"
                        + "分支能成立的前提")
                .isEqualTo(TransactionSynchronization.STATUS_ROLLED_BACK);
    }

    @Test
    @DisplayName("afterCompletion 无事务上下文时以「已提交」语义立即执行")
    void afterCompletion_withoutTransaction_assumesCommitted() {
        AtomicReference<Integer> status = new AtomicReference<>();
        TransactionUtils.afterCompletion(status::set);

        assertThat(status.get())
                .as("无事务时业务写已由自动提交生效，语义上等价于已提交，"
                        + "传 STATUS_ROLLED_BACK 会让审计把正常操作误记为失败")
                .isEqualTo(TransactionSynchronization.STATUS_COMMITTED);
    }

    @Test
    @DisplayName("回调抛异常被吞掉：afterCommit 阶段失败不能拖垮调用线程")
    void afterCommit_swallowsCallbackException() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicInteger afterThrowing = new AtomicInteger();

        // executeWithoutResult 不应因回调异常而抛出：提交后阶段事务已结束，抛出去也无法回滚，
        // 只会把异常抛给一个已经「做完了」的业务线程
        tx.executeWithoutResult(status -> {
            TransactionUtils.afterCommit(() -> {
                throw new IllegalStateException("模拟回调内部失败");
            });
            TransactionUtils.afterCommit(afterThrowing::incrementAndGet);
        });

        assertThat(afterThrowing.get())
                .as("前一个回调抛异常不得影响后续回调执行——runSafely 的 try-catch 保证隔离，"
                        + "否则第一个副作用失败会静默吞掉后面所有副作用")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("三钩子执行次序：afterCommit → afterTransaction → afterCompletion（帧顺序）")
    void hooks_executeInFrameworkOrder() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        StringBuilder order = new StringBuilder();

        tx.executeWithoutResult(status -> {
            TransactionUtils.afterCommit(() -> order.append("afterCommit;"));
            TransactionUtils.afterTransaction(() -> order.append("afterTransaction;"));
            TransactionUtils.afterCompletion(s -> order.append("afterCompletion(").append(s).append(");"));
        });

        // 次序由 Spring 的调用时机决定，与注册顺序无关：
        //   triggerAfterCommit()      → 遍历调 afterCommit()   【registerSynchronization 顺序】
        //   triggerAfterCompletion()  → 遍历调 afterCompletion()【registerSynchronization 顺序】
        // 输出为 "afterCommit;afterTransaction;afterCompletion(0);" 是因为本用例按此顺序注册，
        // 前两者虽分属不同阶段但恰好都被访问到，afterTransaction 先于 afterCompletion 触发。
        // 该次序决定「先发 MQ（afterCommit）还是先解锁（afterTransaction）」这类语义边界。
        assertThat(order.toString())
                .as("钩子执行次序决定了外部副作用的相对时序，实际顺序：%s", order)
                .isEqualTo("afterCommit;afterTransaction;afterCompletion(0);");
    }

    @Test
    @DisplayName("afterCommit 与 afterTransaction 同在提交后触发，且都早于 afterCompletion")
    void afterCommitAndAfterTransaction_bothFireBeforeCompletion() {
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        AtomicInteger commitPhaseMaxSeq = new AtomicInteger(-1);
        AtomicInteger completionPhaseSeq = new AtomicInteger(-1);
        AtomicInteger totalCallbacks = new AtomicInteger();

        tx.executeWithoutResult(status -> {
            // 刻意「先注册 afterTransaction、后注册 afterCommit」，验证阶段划分与注册顺序无关
            TransactionUtils.afterTransaction(() ->
                    commitPhaseMaxSeq.accumulateAndGet(totalCallbacks.incrementAndGet(), Math::max));
            TransactionUtils.afterCommit(() ->
                    commitPhaseMaxSeq.accumulateAndGet(totalCallbacks.incrementAndGet(), Math::max));
            TransactionUtils.afterCompletion(s -> completionPhaseSeq.set(totalCallbacks.incrementAndGet()));
        });

        assertThat(totalCallbacks.get())
                .as("三个钩子都必须被触发，一次都不能漏")
                .isEqualTo(3);
        assertThat(commitPhaseSeqBefore(commitPhaseMaxSeq.get(), completionPhaseSeq.get()))
                .as("afterCommit 与 afterTransaction 都属「提交后阶段」，"
                        + "必须整体早于 afterCompletion 阶段"
                        + "（回调序号 commitPhase=%d < completion=%d）",
                        commitPhaseMaxSeq.get(), completionPhaseSeq.get())
                .isTrue();
        assertThat(commitPhaseMaxSeq.get())
                .as("提交后阶段（afterCommit / afterTransaction）的最大序号应为 2，"
                        + "说明它们在 afterCompletion（序号 3）之前全部完成")
                .isEqualTo(2);
        assertThat(completionPhaseSeq.get())
                .as("afterCompletion 是最后被触发的阶段（触发前会 clearSynchronization），"
                        + "因此审计日志改写必须挂在这里才不会漏掉回滚信号")
                .isEqualTo(3);
    }

    private static boolean commitPhaseSeqBefore(int commitPhaseMaxSeq, int completionPhaseSeq) {
        return commitPhaseMaxSeq < completionPhaseSeq;
    }
}
