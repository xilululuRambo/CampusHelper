package com.rambo.job;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.rambo.BaseApiTest;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.enumType.RetryStatus;
import com.rambo.infrastructure.cache.CacheClient;
import com.rambo.infrastructure.cache.LockClient;
import com.rambo.infrastructure.search.EsSyncOutbox;
import com.rambo.infrastructure.search.EsSyncOutboxService;
import com.rambo.module.goods.server.job.GoodsEsRetryJob;
import com.rambo.module.goods.server.job.GoodsOrderTimeoutJob;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.pojo.entity.NotificationRetry;
import com.rambo.module.notification.server.job.NotificationRetryJob;
import com.rambo.module.notification.server.service.NotificationRetryService;
import com.rambo.module.task.pojo.entity.HotKeywords;
import com.rambo.module.task.pojo.entity.RankTaskMonthly;
import com.rambo.module.task.server.job.HotKeywordsArchiveJob;
import com.rambo.module.task.server.job.TaskEsRetryJob;
import com.rambo.module.task.server.job.TaskRankArchiveJob;
import com.rambo.module.task.server.service.HotKeywordsService;
import com.rambo.module.task.server.service.RankTaskMonthlyService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.jdbc.core.JdbcTemplate;

import cn.hutool.json.JSONUtil;
import javax.sql.DataSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * 定时任务（XXL-JOB handler）测试。
 *
 * <p><b>为什么直接调 bean 方法而不是经调度中心：</b>{@code xxl.job.enabled=false}（测试 profile）
 * 下执行器不启动，无法从调度中心触发。但这些 Job 的 {@code execute()} 本身就是普通 public 方法，
 * 内部逻辑与调度中心无关——直接注入 bean 调用即可覆盖 100% 的分支。反编译 xxl-job-core 2.4.1
 * 确认：{@code XxlJobHelper.handleSuccess/handleResult} 在无 {@code XxlJobContext} 时
 * 只返回 false（不抛异常），因此脱离调度中心调用是安全的，仅丢失「回填执行结果给调度中心」这一动作。</p>
 *
 * <p>本类覆盖六个零引用 Job：两个 ES 发件箱补偿、一个通知重投、一个超时关单、两个归档 Job。
 * 它们的共同特征是「依赖 XXL-JOB 调度触发」，所以此前完全没有测试引用——而它们恰恰是
 * 可靠性链路的最后一道防线（进程崩溃、线程池拒绝、ES 抖动都靠它们收敛）。</p>
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ScheduledJobTest extends BaseApiTest {

    @Resource
    private GoodsEsRetryJob goodsEsRetryJob;
    @Resource
    private TaskEsRetryJob taskEsRetryJob;
    @Resource
    private NotificationRetryJob notificationRetryJob;
    @Resource
    private GoodsOrderTimeoutJob goodsOrderTimeoutJob;
    @Resource
    private HotKeywordsArchiveJob hotKeywordsArchiveJob;
    @Resource
    private TaskRankArchiveJob taskRankArchiveJob;

    @Resource
    private EsSyncOutboxService esSyncOutboxService;
    @Resource
    private NotificationRetryService notificationRetryService;
    @Resource
    private HotKeywordsService hotKeywordsService;
    @Resource
    private RankTaskMonthlyService rankTaskMonthlyService;
    @Resource
    private CacheClient cacheClient;
    @Resource
    private LockClient lockClient;
    @Resource
    private DataSource dataSource;

    private JdbcTemplate jdbc;

    /** 每个用例独占的业务 ID 段，避免与真实数据/历史残留相互干扰 */
    private long baseId;

    @BeforeEach
    void prepare() {
        jdbc = new JdbcTemplate(dataSource);
        baseId = 9_900_000_000_000_000L + (System.nanoTime() % 1_000_000L);
    }

    // ==================== GoodsEsRetryJob / TaskEsRetryJob ====================

    @Test
    @DisplayName("GoodsEsRetryJob 只派发 goods 类型的待同步行，并逐条调用 dispatch")
    void goodsEsRetryJob_dispatchesOnlyGoodsPendingRows() {
        EsSyncOutbox goodsRow = enqueueOutbox("goods", baseId, RetryStatus.PENDING);
        EsSyncOutbox taskRow = enqueueOutbox("task", baseId + 1, RetryStatus.PENDING);
        EsSyncOutbox goodsDone = enqueueOutbox("goods", baseId + 2, RetryStatus.SUCCESS);

        goodsEsRetryJob.execute();

        verify(goodsEsSyncService, times(1)).dispatch(baseId);
        verify(goodsEsSyncService, never()).dispatch(baseId + 1);
        verify(goodsEsSyncService, never()).dispatch(baseId + 2);
        // 关键断言：goods Job 绝不能误派 task 行——dataType 过滤是「各 Job 只管家自己数据」的边界
        verify(taskEsSyncService, never()).dispatch(anyLong());

        cleanupOutbox(goodsRow.getId(), taskRow.getId(), goodsDone.getId());
    }

    @Test
    @DisplayName("TaskEsRetryJob 只派发 task 类型的待同步行")
    void taskEsRetryJob_dispatchesOnlyTaskPendingRows() {
        EsSyncOutbox taskRow = enqueueOutbox("task", baseId + 10, RetryStatus.PENDING);
        EsSyncOutbox goodsRow = enqueueOutbox("goods", baseId + 11, RetryStatus.PENDING);

        taskEsRetryJob.execute();

        verify(taskEsSyncService, times(1)).dispatch(baseId + 10);
        verify(taskEsSyncService, never()).dispatch(baseId + 11);
        verify(goodsEsSyncService, never()).dispatch(anyLong());

        cleanupOutbox(taskRow.getId(), goodsRow.getId());
    }

    @Test
    @DisplayName("单条派发抛异常不中断整批（Job 的容错边界）")
    void esRetryJob_continuesAfterSingleFailure() {
        EsSyncOutbox first = enqueueOutbox("goods", baseId + 20, RetryStatus.PENDING);
        EsSyncOutbox second = enqueueOutbox("goods", baseId + 21, RetryStatus.PENDING);

        // 第一条失败、第二条正常——模拟「某个商品回源时数据异常」这类局部故障
        doAnswer(inv -> {
            throw new IllegalStateException("模拟 dispatch 内部异常");
        }).when(goodsEsSyncService).dispatch(baseId + 20);

        // 不得抛出：Job 内部 try-catch 逐条隔离，否则一条坏数据会让整批待同步行永远卡住
        goodsEsRetryJob.execute();

        verify(goodsEsSyncService).dispatch(baseId + 20);
        verify(goodsEsSyncService, times(1)).dispatch(baseId + 21);

        cleanupOutbox(first.getId(), second.getId());
    }

    /*
     * 通知重投 Job 的测试有两点必须先说明，否则会误读断言：
     *
     * 1. Job 内部用 NotificationRetryService.getWaitRetryList() 拉「全部 PENDING 行」，
     *    没有按 messageId 过滤的能力。因此本类内任何一个留下 PENDING 残留的用例都会污染其他用例。
     *    对策：① 每个用例结束时删掉自己那行；② 本组用例全部用「容忍额外记录」的断言方式——
     *    对目标 messageId 精确 verify，绝不用 times(1) 断言全局调用次数；
     *    ③ 把「锁被占用则跳过」这个需要长期占锁的用例排在最后（@Order）。
     * 2. 断言「锁已释放」必须用 lockProbeSucceeded（走 Redisson 的 Hash 语义），
     *    不能用 CacheClient.setIfAbsent 探测——String 类型写入会让后续 Redisson 调用报 WRONGTYPE。
     */

    @Test
    @Order(1)
    @DisplayName("notificationRetryJob 逐条重投待重试行，并注入发布确认回调")
    void notificationRetryJob_redeliversPendingRows() {
        long messageId = baseId + 100;
        NotificationRetry row = insertRetry(messageId, RetryStatus.PENDING, 0);
        try {
            notificationRetryJob.execute();

            // 精确到目标 messageId：不关心批次里还有没有别的记录（可能被其他用例残留影响）
            verify(rabbitmqProducer, times(1)).sendAfterCommit(
                    anyString(), anyString(), any(), eq(messageId), any());
            assertThat(queryRetryStatus(messageId))
                    .as("确认回调未触发前不得擅自改状态——状态完全由 ack/nack 决定")
                    .isEqualTo(0);
        } finally {
            deleteRetry(messageId);
            org.mockito.Mockito.reset(rabbitmqProducer);
        }
    }

    @Test
    @Order(2)
    @DisplayName("notificationRetryJob 确认失败且达上限：计数+1 → FAILED → 投死信")
    void notificationRetryJob_nackBeyondLimit_movesToDeadLetter() {
        long messageId = baseId + 110;
        // 造一条「已重试 4 次」的记录：再来 1 次失败就应达上限（MAX_RETRY_COUNT=5）
        NotificationRetry row = insertRetry(messageId, RetryStatus.PENDING, 4);
        try {
            // 捕获 Job 注入的确认回调，用 nack 手动触发失败分支（无需真实 broker）
            doAnswer(inv -> {
                java.util.function.BiConsumer<Boolean, String> cb = inv.getArgument(4);
                if (cb != null) {
                    cb.accept(false, "模拟 broker 拒绝");
                }
                return null;
            }).when(rabbitmqProducer).sendAfterCommit(anyString(), anyString(), any(), anyLong(), any());

            notificationRetryJob.execute();

            assertThat(queryRetryCount(messageId))
                    .as("nack 后重试次数必须 +1（4 → 5）")
                    .isEqualTo(5);
            assertThat(queryRetryStatus(messageId))
                    .as("达到上限必须跃迁为 FAILED(2)，否则该消息会被无限重投")
                    .isEqualTo(2);
            verify(rabbitmqProducer, times(1)).sendToDeadLetter(
                    anyString(), anyString(), any(), eq(messageId));
        } finally {
            deleteRetry(messageId);
            org.mockito.Mockito.reset(rabbitmqProducer);
        }
    }

    @Test
    @Order(3)
    @DisplayName("notificationRetryJob 确认成功：PENDING → SUCCESS，不投死信")
    void notificationRetryJob_ack_marksSuccess() {
        long messageId = baseId + 120;
        NotificationRetry row = insertRetry(messageId, RetryStatus.PENDING, 1);
        try {
            doAnswer(inv -> {
                java.util.function.BiConsumer<Boolean, String> cb = inv.getArgument(4);
                if (cb != null) {
                    cb.accept(true, null);
                }
                return null;
            }).when(rabbitmqProducer).sendAfterCommit(anyString(), anyString(), any(), anyLong(), any());

            notificationRetryJob.execute();

            assertThat(queryRetryStatus(messageId))
                    .as("ack 后应标记 SUCCESS(1)")
                    .isEqualTo(1);
            // 不投死信：只对同批次里那些「达到上限」的记录才投，本用例的目标记录不该触发
            verify(rabbitmqProducer, never()).sendToDeadLetter(
                    anyString(), anyString(), any(), eq(messageId));
        } finally {
            deleteRetry(messageId);
            org.mockito.Mockito.reset(rabbitmqProducer);
        }
    }

    @Test
    @Order(4)
    @DisplayName("Job 正常结束后分布式锁必须释放（finally 保证）")
    void notificationRetryJob_releasesLockAfterRun() {
        long messageId = baseId + 140;
        NotificationRetry row = insertRetry(messageId, RetryStatus.PENDING, 0);
        try {
            notificationRetryJob.execute();
        } finally {
            deleteRetry(messageId);
            org.mockito.Mockito.reset(rabbitmqProducer);
        }

        assertThat(lockProbeSucceeded(PrefixConstants.NOTIFICATION_RETRY_LOCK))
                .as("Job 结束后锁必须已释放——否则下次调度会被自己锁死在跳过分支，"
                        + "重试闭环彻底失效且没有任何报错")
                .isTrue();
    }

    @Test
    @Order(5)
    @DisplayName("分布式锁被他人持有时 Job 直接跳过（不重投、不解他人锁）")
    void notificationRetryJob_lockHeldByOthers_skipsEntirely() throws InterruptedException {
        long messageId = baseId + 130;
        NotificationRetry row = insertRetry(messageId, RetryStatus.PENDING, 0);

        // 用 LockClient 在独立线程抢锁来模拟另一个执行器正在跑。
        // 注意：不能用 CacheClient.setIfAbsent 占位——Redisson 的锁是 Hash 结构，
        // 用 String 类型写入同一 key 后再走 Redisson 会报 WRONGTYPE（本测试首版即踩此坑）。
        AtomicReference<Boolean> locked = new AtomicReference<>(false);
        CountDownLatch lockedLatch = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            locked.set(lockClient.tryLock(PrefixConstants.NOTIFICATION_RETRY_LOCK, 0, TimeUnit.SECONDS));
            lockedLatch.countDown();
        });
        holder.start();
        try {
            assertThat(lockedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(locked.get())
                    .as("前置条件：持锁线程应成功抢到锁")
                    .isTrue();

            notificationRetryJob.execute();

            // 跳过时对任何 messageId 都不应发消息——这比精确匹配更强，
            // 因为跳过语义就是整批不处理
            verify(rabbitmqProducer, never()).sendAfterCommit(
                    anyString(), anyString(), any(), anyLong(), any());
            assertThat(queryRetryStatus(messageId))
                    .as("跳过时不得改动任何记录——锁的语义是整批都不处理")
                    .isEqualTo(0);
            assertThat(lockProbeSucceeded(PrefixConstants.NOTIFICATION_RETRY_LOCK))
                    .as("Job 跳过时不得释放他人的锁")
                    .isFalse();
        } finally {
            deleteRetry(messageId);
            joinQuietly(holder);
        }
    }

    // ==================== GoodsOrderTimeoutJob ====================

    @Test
    @DisplayName("超时关单：锁被占用时跳过（不查库、不改单）")
    void goodsOrderTimeoutJob_lockHeld_skips() {
        // 用 LockClient 在独立线程抢锁来模拟「另一个执行器正在跑」。
        // 不能用 CacheClient.setIfAbsent 占位——Redisson 锁是 Hash 结构，
        // 用 String 类型写入同一 key 再走 Redisson 会报 WRONGTYPE（本测试首版踩过此坑）。
        AtomicReference<Boolean> locked = new AtomicReference<>(false);
        CountDownLatch lockedLatch = new CountDownLatch(1);
        Thread holder = new Thread(() -> {
            locked.set(lockClient.tryLock(PrefixConstants.GOODS_ORDER_TIMEOUT_LOCK, 0, TimeUnit.SECONDS));
            lockedLatch.countDown();
        });
        holder.start();
        try {
            assertThat(lockedLatch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(locked.get()).as("前置条件：持锁线程应成功抢到锁").isTrue();

            // 不得抛异常，且必须走「跳过」分支
            goodsOrderTimeoutJob.execute();

            assertThat(lockProbeSucceeded(PrefixConstants.GOODS_ORDER_TIMEOUT_LOCK))
                    .as("跳过时不得解锁他人的锁——否则两个执行器会同时进入关单临界区，"
                            + "对同一批订单重复取消")
                    .isFalse();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError("等待持锁线程被中断", e);
        } finally {
            joinQuietly(holder);
        }
    }

    @Test
    @DisplayName("超时关单：无过期订单时正常空跑并释放锁")
    void goodsOrderTimeoutJob_noExpiredOrders_releasesLock() {
        // 测试库 t_goods_order 无「超时未付款」数据（探测确认），Job 应走空跑分支
        goodsOrderTimeoutJob.execute();

        assertThat(lockProbeSucceeded(PrefixConstants.GOODS_ORDER_TIMEOUT_LOCK))
                .as("正常结束后锁必须释放，否则后续调度全部被跳过")
                .isTrue();
    }

    // ==================== HotKeywordsArchiveJob ====================

    @Test
    @DisplayName("热搜归档：当天 key RENAME 后入库，归档 key 清理，Top 顺序与分数保留")
    void hotKeywordsArchiveJob_archivesAndCleansUp() {
        LocalDate target = LocalDate.now().minusDays(1);
        // 兜底：清掉可能存在的历史残留，让断言只反映本次写入
        deleteHotKeywords(target);
        deleteHotKeywords(LocalDate.now());
        String archiveKey = PrefixConstants.HOT_KEYWORDS_ARCHIVE + target.format(DateTimeFormatter.ISO_LOCAL_DATE);
        cacheClient.delete(PrefixConstants.HOT_KEYWORDS);
        cacheClient.delete(archiveKey);

        // 造数据：故意乱序写入，验证归档后仍按分数倒序落库
        cacheClient.zIncrementScore(PrefixConstants.HOT_KEYWORDS, "k-low", 1);
        cacheClient.zIncrementScore(PrefixConstants.HOT_KEYWORDS, "k-high", 30);
        cacheClient.zIncrementScore(PrefixConstants.HOT_KEYWORDS, "k-mid", 10);

        try {
            hotKeywordsArchiveJob.execute();

            assertThat(cacheClient.hasKey(PrefixConstants.HOT_KEYWORDS))
                    .as("归档后当天 key 必须消失（RENAME 的原子语义），新搜索写入新 key")
                    .isFalse();
            assertThat(cacheClient.hasKey(archiveKey))
                    .as("入库成功后归档 key 必须被清理，否则下次调度会当孤儿重复收编")
                    .isFalse();

            List<HotKeywords> saved = hotKeywordsService.lambdaQuery()
                    .eq(HotKeywords::getRecordDate, target)
                    .orderByDesc(HotKeywords::getSearchCount)
                    .list();
            assertThat(saved)
                    .as("入库条数应与 ZSet 成员数一致")
                    .hasSize(3);
            assertThat(saved).extracting(HotKeywords::getKeyword)
                    .as("归档必须保持热搜名次顺序（Redis 倒序读取 → 落库）")
                    .containsExactly("k-high", "k-mid", "k-low");
            assertThat(saved.get(0).getSearchCount()).isEqualTo(30);
        } finally {
            cacheClient.delete(PrefixConstants.HOT_KEYWORDS);
            cacheClient.delete(archiveKey);
            deleteHotKeywords(target);
        }
    }

    @Test
    @DisplayName("热搜归档：孤儿归档 key 被收编，且日期从 key 解析（非「本次调度的昨天」）")
    void hotKeywordsArchiveJob_adoptsOrphanKeyByParsedDate() {
        // 造一个「上次 RENAME 成功但入库失败」的孤儿：归档 key 存在，对应日期是 3 天前
        LocalDate orphanDate = LocalDate.now().minusDays(3);
        deleteHotKeywords(orphanDate);
        deleteHotKeywords(LocalDate.now().minusDays(1));
        String orphanKey = PrefixConstants.HOT_KEYWORDS_ARCHIVE
                + orphanDate.format(DateTimeFormatter.ISO_LOCAL_DATE);
        cacheClient.delete(orphanKey);
        cacheClient.delete(PrefixConstants.HOT_KEYWORDS);
        cacheClient.zIncrementScore(orphanKey, "orphan-kw", 7);

        try {
            hotKeywordsArchiveJob.execute();

            assertThat(cacheClient.hasKey(orphanKey))
                    .as("孤儿 key 被收编后必须清理")
                    .isFalse();
            List<HotKeywords> adopted = hotKeywordsService.lambdaQuery()
                    .eq(HotKeywords::getRecordDate, orphanDate)
                    .list();
            assertThat(adopted)
                    .as("孤儿数据必须落在「key 里解析出的日期」上——"
                            + "若用「本次调度的昨天」会给跨天重试的数据记错日期，"
                            + "还会因幂等判定把当天的真实数据误当重复而丢弃")
                    .hasSize(1);
            assertThat(adopted.get(0).getKeyword()).isEqualTo("orphan-kw");
            assertThat(adopted.get(0).getSearchCount()).isEqualTo(7);
        } finally {
            cacheClient.delete(orphanKey);
            cacheClient.delete(PrefixConstants.HOT_KEYWORDS);
            deleteHotKeywords(orphanDate);
        }
    }

    @Test
    @DisplayName("热搜归档：当天无搜索时直接跳过（RENAME 不存在的 key 会失败）")
    void hotKeywordsArchiveJob_noData_skipsRename() {
        cacheClient.delete(PrefixConstants.HOT_KEYWORDS);
        LocalDate target = LocalDate.now().minusDays(1);
        String archiveKey = PrefixConstants.HOT_KEYWORDS_ARCHIVE + target.format(DateTimeFormatter.ISO_LOCAL_DATE);
        cacheClient.delete(archiveKey);
        deleteHotKeywords(target);

        // 不得抛异常：Redis RENAME 对不存在的 key 会报错，Job 必须先 hasKey 短路
        hotKeywordsArchiveJob.execute();

        assertThat(cacheClient.hasKey(archiveKey))
                .as("无数据时不应凭空产生归档 key")
                .isFalse();
    }

    @Test
    @DisplayName("热搜归档幂等：同一日期已有归档记录时跳过入库但清理 key")
    void hotKeywordsArchiveJob_idempotentOnExistingDate() {
        LocalDate target = LocalDate.now().minusDays(1);
        deleteHotKeywords(target);
        String archiveKey = PrefixConstants.HOT_KEYWORDS_ARCHIVE + target.format(DateTimeFormatter.ISO_LOCAL_DATE);
        cacheClient.delete(archiveKey);
        cacheClient.delete(PrefixConstants.HOT_KEYWORDS);

        // 先塞一条该日期的已有归档记录，模拟「上次已入库但删 key 前进程崩溃」
        HotKeywords existing = new HotKeywords();
        existing.setKeyword("already-archived");
        existing.setSearchCount(99);
        existing.setRecordDate(target);
        existing.setUpdateTime(LocalDateTime.now());
        hotKeywordsService.save(existing);

        cacheClient.zIncrementScore(PrefixConstants.HOT_KEYWORDS, "should-be-skipped", 5);

        try {
            hotKeywordsArchiveJob.execute();

            List<HotKeywords> rows = hotKeywordsService.lambdaQuery()
                    .eq(HotKeywords::getRecordDate, target)
                    .list();
            assertThat(rows)
                    .as("已有归档的日期不得重复入库——否则 uk_keyword_date 唯一键会撞键，"
                            + "收编流程永远失败、孤儿 key 永久滞留")
                    .hasSize(1);
            assertThat(rows.get(0).getKeyword()).isEqualTo("already-archived");
            assertThat(cacheClient.hasKey(archiveKey))
                    .as("幂等跳过后仍须清理归档 key，避免孤儿永久滞留")
                    .isFalse();
            assertThat(cacheClient.hasKey(PrefixConstants.HOT_KEYWORDS))
                    .as("已被 RENAME 走的当天 key 不应复活")
                    .isFalse();
        } finally {
            cacheClient.delete(PrefixConstants.HOT_KEYWORDS);
            cacheClient.delete(archiveKey);
            deleteHotKeywords(target);
        }
    }

    // ==================== TaskRankArchiveJob ====================

    @Test
    @DisplayName("月榜归档：上月 key RENAME → Top10 入库 → 归档 key 清理")
    void taskRankArchiveJob_archivesTopTen() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        deleteRank(lastMonth);
        String key = PrefixConstants.TASK_RANK_MONTH + lastMonth;
        String archiveKey = PrefixConstants.TASK_RANK_MONTH_ARCHIVE + lastMonth;
        cacheClient.delete(key);
        cacheClient.delete(archiveKey);

        // 造 3 个用户，分数故意乱序
        cacheClient.zIncrementScore(key, "900000000000000001", 3);
        cacheClient.zIncrementScore(key, "900000000000000002", 9);
        cacheClient.zIncrementScore(key, "900000000000000003", 6);

        try {
            taskRankArchiveJob.execute();

            assertThat(cacheClient.hasKey(key)).as("归档后当月 key 必须消失").isFalse();
            assertThat(cacheClient.hasKey(archiveKey)).as("入库成功后归档 key 必须清理").isFalse();

            List<RankTaskMonthly> saved = rankTaskMonthlyService.lambdaQuery()
                    .eq(RankTaskMonthly::getMonth, lastMonth)
                    .orderByAsc(RankTaskMonthly::getRankNum)
                    .list();
            assertThat(saved).hasSize(3);
            assertThat(saved).extracting(RankTaskMonthly::getUserId)
                    .as("月榜按分数倒序归档，rank_num 递增")
                    .containsExactly(900000000000000002L, 900000000000000003L, 900000000000000001L);
            assertThat(saved).extracting(RankTaskMonthly::getRankNum)
                    .containsExactly(1, 2, 3);
            assertThat(saved.get(0).getFinishCount()).isEqualTo(9);
        } finally {
            cacheClient.delete(key);
            cacheClient.delete(archiveKey);
            deleteRank(lastMonth);
        }
    }

    @Test
    @DisplayName("月榜归档：上月无数据时跳过 RENAME")
    void taskRankArchiveJob_noData_skips() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key = PrefixConstants.TASK_RANK_MONTH + lastMonth;
        String archiveKey = PrefixConstants.TASK_RANK_MONTH_ARCHIVE + lastMonth;
        cacheClient.delete(key);
        cacheClient.delete(archiveKey);
        deleteRank(lastMonth);

        taskRankArchiveJob.execute();

        assertThat(cacheClient.hasKey(archiveKey)).isFalse();
    }

    @Test
    @DisplayName("月榜归档：孤儿归档 key 被收编（上次入库失败残留）")
    void taskRankArchiveJob_adoptsOrphanKey() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key = PrefixConstants.TASK_RANK_MONTH + lastMonth;
        String archiveKey = PrefixConstants.TASK_RANK_MONTH_ARCHIVE + lastMonth;
        cacheClient.delete(key);
        cacheClient.delete(archiveKey);
        deleteRank(lastMonth);

        // 孤儿：只有归档 key，没有当月 key（正是上次 RENAME 后入库失败的状态）
        cacheClient.zIncrementScore(archiveKey, "900000000000000011", 4);

        try {
            taskRankArchiveJob.execute();

            assertThat(cacheClient.hasKey(archiveKey)).as("孤儿被收编后必须清理").isFalse();
            List<RankTaskMonthly> saved = rankTaskMonthlyService.lambdaQuery()
                    .eq(RankTaskMonthly::getMonth, lastMonth)
                    .list();
            assertThat(saved)
                    .as("孤儿数据必须被入库，而不是因为「当月 key 不存在」被直接跳过——"
                            + "这正是步骤 0 收编逻辑存在的理由")
                    .hasSize(1);
            assertThat(saved.get(0).getUserId()).isEqualTo(900000000000000011L);
        } finally {
            cacheClient.delete(key);
            cacheClient.delete(archiveKey);
            deleteRank(lastMonth);
        }
    }

    @Test
    @DisplayName("月榜归档幂等：该月已有归档用户时过滤补插，不全量重插")
    void taskRankArchiveJob_idempotentByUserMonth() {
        String lastMonth = LocalDate.now().minusMonths(1).format(DateTimeFormatter.ofPattern("yyyy-MM"));
        String key = PrefixConstants.TASK_RANK_MONTH + lastMonth;
        String archiveKey = PrefixConstants.TASK_RANK_MONTH_ARCHIVE + lastMonth;
        cacheClient.delete(key);
        cacheClient.delete(archiveKey);
        deleteRank(lastMonth);

        // 已有用户 A 的归档（模拟上次部分成功）
        RankTaskMonthly already = new RankTaskMonthly();
        already.setUserId(900000000000000021L);
        already.setFinishCount(2);
        already.setRankNum(1);
        already.setMonth(lastMonth);
        rankTaskMonthlyService.save(already);

        // 本次归档包含 A 与 B：A 应被过滤，B 应补插
        cacheClient.zIncrementScore(key, "900000000000000021", 2);
        cacheClient.zIncrementScore(key, "900000000000000022", 5);

        try {
            taskRankArchiveJob.execute();

            List<RankTaskMonthly> rows = rankTaskMonthlyService.lambdaQuery()
                    .eq(RankTaskMonthly::getMonth, lastMonth)
                    .list();
            assertThat(rows)
                    .as("已有用户不得重复插入（uk_user_month 会撞键），仅补插缺失用户——"
                            + "这样「部分入库」的场景也能自愈")
                    .hasSize(2);
            assertThat(rows).extracting(RankTaskMonthly::getUserId)
                    .containsExactlyInAnyOrder(900000000000000021L, 900000000000000022L);
            assertThat(cacheClient.hasKey(archiveKey)).as("补插后归档 key 应清理").isFalse();
        } finally {
            cacheClient.delete(key);
            cacheClient.delete(archiveKey);
            deleteRank(lastMonth);
        }
    }

    // ==================== 辅助方法 ====================

    private EsSyncOutbox enqueueOutbox(String dataType, long dataId, RetryStatus status) {
        EsSyncOutbox row = new EsSyncOutbox();
        row.setDataId(dataId);
        row.setDataType(dataType);
        row.setOpType(com.rambo.common.enumType.EsSyncOp.UPSERT);
        row.setEsVersion(System.currentTimeMillis());
        row.setRetryCount(0);
        row.setStatus(status);
        esSyncOutboxService.save(row);
        return row;
    }

    private void cleanupOutbox(Long... ids) {
        for (Long id : ids) {
            if (id != null) {
                esSyncOutboxService.removeById(id);
            }
        }
        resetEsSyncMocks();
    }

    /** Job 用例执行完要清掉 mock 调用记录，避免 verify 跨用例累计 */
    private void resetEsSyncMocks() {
        org.mockito.Mockito.reset(goodsEsSyncService, taskEsSyncService);
    }

    private NotificationRetry insertRetry(long messageId, RetryStatus status, int retryCount) {
        NotificationMessage msg = NotificationMessage.builder()
                .messageId(messageId)
                .userId(1L)
                .content("定时任务测试消息")
                .refId(1L)
                .build();
        NotificationRetry row = new NotificationRetry();
        row.setMessageId(messageId);
        row.setMessageBody(JSONUtil.toJsonStr(msg));
        row.setStatus(status);
        row.setRetryCount(retryCount);
        row.setErrorMessage("probe");
        notificationRetryService.save(row);
        return row;
    }

    private int queryRetryStatus(long messageId) {
        Integer s = jdbc.queryForObject(
                "SELECT status FROM t_notification_retry WHERE message_id = ?", Integer.class, messageId);
        return s == null ? -1 : s;
    }

    private int queryRetryCount(long messageId) {
        Integer c = jdbc.queryForObject(
                "SELECT retry_count FROM t_notification_retry WHERE message_id = ?", Integer.class, messageId);
        return c == null ? -1 : c;
    }

    private void deleteRetry(long messageId) {
        jdbc.update("DELETE FROM t_notification_retry WHERE message_id = ?", messageId);
    }

    private void deleteHotKeywords(LocalDate date) {
        jdbc.update("DELETE FROM t_hot_keywords WHERE record_date = ?", java.sql.Date.valueOf(date));
    }

    private void deleteRank(String month) {
        jdbc.update("DELETE FROM t_task_rank_monthly WHERE month = ?", month);
    }

    /**
     * 探测锁是否处于「可被当前线程抢到」的状态。
     *
     * <p>返回 true = 锁已释放（可抢）；false = 仍被他人持有。
     * 用 LockClient 而非 CacheClient 探测，因为 Redisson 锁是 Hash 结构，
     * 用 String 语义读同一 key 会报 WRONGTYPE。</p>
     */
    private boolean lockProbeSucceeded(String lockKey) {
        AtomicReference<Boolean> got = new AtomicReference<>(false);
        CountDownLatch done = new CountDownLatch(1);
        Thread prober = new Thread(() -> {
            got.set(lockClient.tryLock(lockKey, 0, TimeUnit.SECONDS));
            if (Boolean.TRUE.equals(got.get())) {
                lockClient.unlock(lockKey);
            }
            done.countDown();
        });
        prober.start();
        try {
            done.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Boolean.TRUE.equals(got.get());
    }

    private static void joinQuietly(Thread t) {
        try {
            t.join(5_000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
