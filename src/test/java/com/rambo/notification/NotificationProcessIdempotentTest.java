package com.rambo.notification;

import com.rambo.BaseApiTest;
import com.rambo.module.notification.enums.NotificationType;
import com.rambo.module.notification.pojo.dto.NotificationMessage;
import com.rambo.module.notification.pojo.entity.Notification;
import com.rambo.module.notification.pojo.entity.NotificationRetry;
import com.rambo.module.notification.server.service.NotificationProcessor;
import com.rambo.module.notification.server.service.NotificationRetryService;
import com.rambo.module.notification.server.service.NotificationService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * 通知消费幂等测试：{@code NotificationProcessorImpl#process}。
 *
 * <p><b>这块为什么此前是空白</b>：MQ 整条链路（生产者确认回调 / 消费者 ack 策略 / 重试表补偿）
 * 都被标注为「需真实 RabbitMQ」，一直没有测试。但消费端真正决定「消息会不会重复落库」
 * 的那段逻辑——{@code NotificationProcessorImpl#process} 的幂等三段式——其实是纯 Java +
 * 一次 DB 写，完全可以脱离 broker 单独验证。它恰好也是整条链路里**唯一不许出错**的一环：
 * RabbitMQ 本身就是 at-least-once 语义，重复投递是常态而非异常，幂等失效的直接后果是
 * 用户收到多条同样的站内信，且唯一索引一旦缺失就会静默重复。</p>
 *
 * <p><b>三段幂等防线</b>（本测试逐条命中）：</p>
 * <ol>
 *   <li>预检 {@code existsByMessageId} —— 快速路径，绝大多数重复消息死在这里；</li>
 *   <li>唯一索引兜底 —— 预检与插入之间的并发窗口，靠 {@code DuplicateKeyException} 收敛；</li>
 *   <li>落库异常写重试表 —— 不是「重复」而是「失败」，必须走补偿而非静默丢弃。</li>
 * </ol>
 *
 * <p>第 2、3 条无法通过真实数据构造（前者要求精确的并发交错，后者要求 DB 故障），
 * 因此用 spy 精确注入对应异常——这是本项目「探针式测试」的一贯做法：
 * 被测的是分支逻辑本身，而非触发它的偶然条件。</p>
 */
class NotificationProcessIdempotentTest extends BaseApiTest {

    @Resource
    private NotificationProcessor notificationProcessor;

    @Resource
    private NotificationService notificationService;

    @Resource
    private NotificationRetryService notificationRetryService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    /**
     * 生成测试专用的 messageId：固定在 9e15 以上的高位区间，绝不可能与业务消息撞车。
     *
     * <p><b>两次踩坑记录（这段注释是排查过程本身的价值）</b>：</p>
     * <ol>
     *   <li>初版用 {@code 9_000_000_000_000L + nanoTime %% 1e9}，看似安全，实则
     *       nanoTime 取模后低位变化不剧烈，同一 JVM 内连续调用频繁碰撞，
     *       前一用例的残留行会占住 message_id；</li>
     *   <li>二版改用 {@code (millis %% 1e8) * 1000 + seq}，值域是 5e10 量级——
     *      表面上"单调递增"，却**落进了真实业务 messageId 的取值范围**
     *       （库里确实存在 message_id=53642979005 的业务行），于是撞 PRIMARY 之外的行。
     *       更隐蔽的是：失败信息报的是 PRIMARY 冲突，把排查方向完全带偏。</li>
     * </ol>
     * <p>最终方案：加 9e15 量级的高位前缀，与任何业务 ID 空间（雪花ID≈2e18 但那是主键、
     * 业务 messageId 目前是 5e10 量级）都不重叠，同时保留自增序号保证唯一。</p>
     */
    private static final long TEST_MESSAGE_ID_BASE = 9_000_000_000_000_000L;

    private static final int SEQ_BASE = (int) (System.currentTimeMillis() % 100_000L) * 100 + 1000;

    private static final java.util.concurrent.atomic.AtomicInteger SEQ =
            new java.util.concurrent.atomic.AtomicInteger(SEQ_BASE);

    private long nextMessageId() {
        return TEST_MESSAGE_ID_BASE + SEQ.incrementAndGet();
    }

    /**
     * 清掉本类历史运行的残留。
     *
     * <p>为什么必须清：messageId 用「固定高位前缀 + 自增序号」，序号每次都从 SEQ_BASE 起算。
     * 上一轮运行留下的行会占住同一批 messageId，下一次运行第一步就撞唯一索引，
     * 表现为「测试第一次跑绿、第二次跑红」这种最难查的间歇性失败。
     * 只删本类专用高位区间（&gt; 9e15），不触碰任何业务数据。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void cleanPreviousRun() {
        // 按主键区间清（主键是本类自造的，一定落在 PK_BASE 之上）
        jdbcTemplate.update("DELETE FROM t_notification WHERE id >= " + PK_BASE);
        jdbcTemplate.update("DELETE FROM t_notification WHERE message_id > " + TEST_MESSAGE_ID_BASE);
        jdbcTemplate.update("DELETE FROM t_notification_retry WHERE message_id > " + TEST_MESSAGE_ID_BASE);
        // 计数器同步归零，保证每个用例都从同一小段主键区间开始（清过库，复用是安全的）
        PK.set(PK_BASE);
    }

    private NotificationMessage message(long messageId, Long userId) {
        return NotificationMessage.builder()
                .messageId(messageId)
                .userId(userId)
                .type(NotificationType.TASK_NEW_APPLY)
                .content("幂等测试消息")
                .refId(1L)
                .build();
    }

    // ==================== 正常落库 ====================

    @Test
    @DisplayName("首次消费：通知落库，且 messageId / userId / 内容完整")
    void firstConsume_persistsNotification() {
        Long userId = newAuthedUser().getUserId();
        long messageId = nextMessageId();

        notificationProcessor.process(message(messageId, userId));

        List<Notification> rows = notificationService.lambdaQuery()
                .eq(Notification::getMessageId, messageId).list();

        assertThat(rows).as("首次消费必须落一条通知").hasSize(1);
        Notification saved = rows.get(0);
        assertThat(saved.getUserId()).isEqualTo(userId);
        assertThat(saved.getContent()).isEqualTo("幂等测试消息");
        assertThat(saved.getType()).isEqualTo(NotificationType.TASK_NEW_APPLY);
        assertThat(saved.getRefId()).isEqualTo(1L);
        assertThat(saved.getCreateTime())
                .as("createTime 应由 MyMetaObjectHandler 自动填充（消费端同样走填充链路）")
                .isNotNull();
    }

    // ==================== 防线一：预检 ====================

    @Test
    @DisplayName("防线一 · 预检命中：同一 messageId 第二次消费不落库")
    void duplicate_byPreexistingRecord_ignored() {
        Long userId = newAuthedUser().getUserId();
        long messageId = nextMessageId();

        notificationProcessor.process(message(messageId, userId));
        notificationProcessor.process(message(messageId, userId));

        assertThat(countNotifications(messageId))
                .as("MQ 是 at-least-once，同一条消息重投是常态。预检必须把它挡在插库之前，"
                        + "否则用户会收到重复站内信")
                .isEqualTo(1);
    }

    @Test
    @DisplayName("防线一 · 预检命中时不得写入重试表（重复 ≠ 失败）")
    void duplicate_doesNotWriteRetryRecord() {
        Long userId = newAuthedUser().getUserId();
        long messageId = nextMessageId();

        notificationProcessor.process(message(messageId, userId));
        notificationProcessor.process(message(messageId, userId));

        assertThat(countRetry(messageId))
                .as("重复消息是「已处理」，不是「处理失败」；若被写进重试表，"
                        + "重投 Job 会把它当失败反复派发，把一个重复膨胀成无限重试")
                .isZero();
    }

    // ==================== 防线二：唯一索引兜底 ====================

    /**
     * 防线二 · 并发窗口。
     *
     * <p><b>索引核对结论（曾被我误判，此处留档）</b>：{@code t_notification.message_id} 上
     * 的约束**确实是唯一索引** —— {@code information_schema.statistics} 显示
     * {@code idx_message_id.non_unique = 0}，对应 {@code schema.sql:224-225} 的
     * {@code constraint idx_message_id unique (message_id)}。
     * 它名字带 {@code idx_} 前缀却实为 UNIQUE 约束，是极易误判的命名陷阱
     * （教训：核对索引必须看 {@code information_schema} 的 {@code non_unique}，
     * 不能凭索引名前缀猜）。</p>
     *
     * <p>本用例验证：预检失效（模拟并发窗口内两个线程都没查到）时，
     * 唯一索引会拦下第二次插入，{@code DuplicateKeyException} 被吞掉、不进重试表。</p>
     */
    @Test
    @DisplayName("防线二 · 并发窗口：插入撞 message_id 唯一索引被识别为「已处理」，不抛不重试")
    void duplicate_byUniqueIndex_swallowed() {
        Long userId = newAuthedUser().getUserId();
        long messageId = nextMessageId();

        // 真实库里先放一条同 messageId 的行：模拟「预检时不存在、插入时对方已插入」的交错
        notificationService.saveNotification(buildEntity(messageId, userId));

        // Service 替身：预检恒返回 false（假装两个线程都没查到），插入委托真身 → 必撞唯一索引
        NotificationService stub = mock(NotificationService.class);
        when(stub.existsByMessageId(messageId)).thenReturn(false);
        doAnswer(inv -> {
            notificationService.saveNotification(copyOf(inv.getArgument(0)));
            return null;
        }).when(stub).saveNotification(any(Notification.class));

        // 关键：撞索引必须被吞掉，不能抛出去——抛出去会让消费者走异常分支、消息被反复重投
        newProcessorWith(stub).process(message(messageId, userId));

        assertThat(countNotifications(messageId))
                .as("预检失效时靠 message_id 唯一索引收敛竞态，最终仍只有一条")
                .isEqualTo(1);
        assertThat(countRetry(messageId))
                .as("撞唯一索引属于「重复」，不是「落库失败」，不得进重试表——"
                        + "否则重投 Job 会把它当失败反复派发，把一个重复膨胀成无限重试")
                .isZero();
    }

    @Test
    @DisplayName("防线二 · 索引现状固化：message_id 必须是唯一索引（process 的并发原子性依赖它）")
    void messageIdIndex_mustBeUnique() {
        Long uniqueCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() AND table_name = 't_notification' "
                        + "AND column_name = 'message_id' AND non_unique = 0", Long.class);

        assertThat(uniqueCount)
                .as("process() 注释声明「真正的并发原子性由 t_notification.message_id 唯一索引保证」，"
                        + "该索引一旦被误删或降级为普通索引，并发重复消息会静默落库两条，"
                        + "而开着的那个 catch (DuplicateKeyException) 分支会变成死代码")
                .isEqualTo(1L);
    }

    // ==================== 防线三：落库失败转重试表 ====================

    @Test
    @DisplayName("防线三 · 落库失败：写入重试表补偿，而不是静默丢弃")
    void saveFailure_writesRetryRecord() {
        Long userId = newAuthedUser().getUserId();
        long messageId = nextMessageId();

        // 用纯 mock 让 saveNotification 恒抛（不用 spy：Spring 的 CGLIB 代理会让 spy 无法解包）
        NotificationService broken = mock(NotificationService.class);
        when(broken.existsByMessageId(messageId)).thenReturn(false);
        doThrow(new RuntimeException("模拟 DB 连接中断"))
                .when(broken).saveNotification(any(Notification.class));

        NotificationProcessor brokenProcessor = newProcessorWith(broken);
        brokenProcessor.process(message(messageId, userId));

        assertThat(countNotifications(messageId))
                .as("落库失败，通知表里当然没有").isZero();

        List<NotificationRetry> retries = notificationRetryService.lambdaQuery()
                .eq(NotificationRetry::getMessageId, messageId).list();

        assertThat(retries)
                .as("落库失败必须留痕到重试表，由重投 Job 补偿——"
                        + "若这里被静默吞掉，用户将永久收不到这条通知且无人知晓")
                .hasSize(1);
        assertThat(retries.get(0).getStatus())
                .as("初始状态应为 PENDING，等待重投 Job 认领")
                .isEqualTo(com.rambo.common.enumType.RetryStatus.PENDING);
        assertThat(retries.get(0).getRetryCount()).isZero();
    }

    @Test
    @DisplayName("防线三 · 落库失败不抛出：消费线程必须能继续 ack 后续消息")
    void saveFailure_doesNotPropagate() {
        Long userId = newAuthedUser().getUserId();
        long messageId = nextMessageId();

        NotificationService broken = mock(NotificationService.class);
        when(broken.existsByMessageId(messageId)).thenReturn(false);
        doThrow(new RuntimeException("模拟 DB 连接中断"))
                .when(broken).saveNotification(any(Notification.class));

        NotificationProcessor brokenProcessor = newProcessorWith(broken);

        // 不抛异常即通过——消费端 onMessage 的 catch 只是防御性兜底，
        // 处理器本身就该把已识别的业务失败消化掉
        brokenProcessor.process(message(messageId, userId));
    }

    // ==================== 幂等键的边界 ====================

    @Test
    @DisplayName("不同 messageId 的同内容消息：两条都落库（幂等键是 messageId，不是内容）")
    void differentMessageIds_bothPersisted() {
        Long userId = newAuthedUser().getUserId();
        long idA = nextMessageId();
        long idB = nextMessageId();

        notificationProcessor.process(message(idA, userId));
        notificationProcessor.process(message(idB, userId));

        assertThat(countNotifications(idA)).isEqualTo(1);
        assertThat(countNotifications(idB))
                .as("幂等判定必须只看 messageId。若误用『内容+用户』去重，"
                        + "同一任务的多次同类提醒会被错误合并")
                .isEqualTo(1);
    }

    // ==================== 辅助 ====================

    /**
     * 测试专用主键区间起点：{@code 9_200_000_000_000_000} 以上全部空置，
     * 配合 {@code @BeforeEach} 的区间清理，每次运行都从干净区间开始。
     */
    private static final long PK_BASE = 9_200_000_000_000_000L;

    private static final java.util.concurrent.atomic.AtomicLong PK =
            new java.util.concurrent.atomic.AtomicLong(PK_BASE);

    /**
     * 生成测试专用主键。
     *
     * <p><b>为什么不交给 MyBatis-Plus 生成</b>：项目的 {@code @TableId(IdType.ASSIGN_ID)}
     * 走默认雪花算法，在「同一毫秒内连续两次 save」的测试场景下会产出相同 id 直接撞 PRIMARY。
     * 纯自增 AtomicLong 最简单、最可预测。</p>
     */
    private static long nextPk() {
        return PK.incrementAndGet();
    }

    /**
     * 构造一条「预置行」实体，<b>只构造不落库</b>。
     *
     * <p>落库动作交给调用方显式执行（如 {@code saveNotification(buildEntity(...))}）。
     * 这里曾经把 {@code saveNotification} 写在方法内部，而调用点又套了一层
     * {@code saveNotification(buildEntity(...))}，同一个 id 被插两次直接撞
     * PRIMARY（{@code Duplicate entry '9200000000000001'}）——失败信息指着主键，
     * 看起来像「主键生成器出错」，实际是重复插入。排查时被这条误导了很久，
     * 因此把「构造」与「落库」显式分开，不留这种能自我欺骗的重叠。</p>
     */
    private Notification buildEntity(long messageId, Long userId) {
        Notification n = copyOf(null);
        n.setMessageId(messageId);
        n.setUserId(userId);
        n.setContent("预置行");
        return n;
    }

    /**
     * 复制一条新实体并**显式指定主键**。
     *
     * <p>为什么不交给 MyBatis-Plus 生成 id：项目的 {@code @TableId(IdType.ASSIGN_ID)} 走默认雪花算法，
     * 在测试这种「同一毫秒内连续两次 save」的场景下会产出相同 id，直接撞 PRIMARY，
     * 把一次关于 message_id 幂等的验证变成主键冲突，排查方向被严重带偏（本人已在此踩两次）。
     * 显式给主键可让被测分支（DuplicateKeyException 兜底）与主键生成解耦。</p>
     */
    private Notification copyOf(Notification source) {
        Notification n = new Notification();
        n.setId(nextPk());
        n.setType(NotificationType.TASK_NEW_APPLY);
        n.setRefId(1L);
        if (source != null) {
            n.setMessageId(source.getMessageId());
            n.setUserId(source.getUserId());
            n.setType(source.getType());
            n.setContent(source.getContent());
            n.setRefId(source.getRefId());
        }
        return n;
    }

    /**
     * 用指定的 NotificationService 替身重建一个处理器实例。
     * <p>该处理器只有 {@code notificationService} 一个依赖会参与幂等判定，
     * 其余依赖（WsMessenger / taskExecutor）保持真实——推送走异步且失败静默，
     * 不影响本类断言的落库结论。</p>
     */
    private NotificationProcessor newProcessorWith(NotificationService service) {
        NotificationProcessor processor = new com.rambo.module.notification.server.service.impl
                .NotificationProcessorImpl();
        setField(processor, "notificationService", service);
        setField(processor, "wsMessenger", org.mockito.Mockito.mock(
                com.rambo.infrastructure.websocket.WsMessenger.class));
        setField(processor, "notificationRetryService", notificationRetryService);
        setField(processor, "taskExecutor",
                (java.util.concurrent.Executor) Runnable::run);
        return processor;
    }

    private int countNotifications(long messageId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification WHERE message_id = ?", Integer.class, messageId);
        return n == null ? 0 : n;
    }

    private int countRetry(long messageId) {
        Integer n = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM t_notification_retry WHERE message_id = ?", Integer.class, messageId);
        return n == null ? 0 : n;
    }
}
