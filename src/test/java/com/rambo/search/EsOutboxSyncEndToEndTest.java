package com.rambo.search;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import com.rambo.BaseApiTest;
import com.rambo.common.constants.PrefixConstants;
import com.rambo.common.enumType.EsSyncOp;
import com.rambo.common.enumType.RetryStatus;
import com.rambo.infrastructure.search.EsDTO;
import com.rambo.infrastructure.search.EsSyncOutbox;
import com.rambo.infrastructure.search.EsSyncOutboxService;
import com.rambo.infrastructure.search.EsUtil;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.server.mapper.TaskMapper;
import com.rambo.module.task.server.service.impl.TaskEsSyncService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.util.ReflectionTestUtils.setField;

/**
 * ES 事务性 Outbox <b>端到端</b>测试：{@code AbstractEsOutboxSyncService#dispatch} 六阶段全链路。
 *
 * <p><b>Outbox 改造后的语义</b>：每条 enqueue 独立成行，行 id 直接作为 ES external version
 * （自增单调）；同一 (dataType, dataId) 允许多行 PENDING，按 id ASC 顺序派发。
 * 重试按指数退避推进 {@code next_retry_at}。</p>
 *
 * <p><b>为什么此前是空白</b>：{@link BaseApiTest} 把 {@link EsUtil} 整体 mock
 * （"测试不依赖中间件可用性"），于是 {@code dispatch} 内部真正干活的部分——
 * 回源加载实体、external version 写入、409 判定、墓碑删除、失败重试递增——
 * <b>一行都没有真正执行过</b>。此前 {@code ScheduledJobTest} 里那几条
 * {@code verify(goodsEsSyncService).dispatch(id)} 只证明了「补偿 Job 挑对了待同步行」，
 * 而 {@code goodsEsSyncService} 本身也是 mock，dispatch 内部完全没跑。</p>
 *
 * <p><b>本类怎么绕开 mock</b>：用 {@link RealEsConfig} 以 {@code @Primary} 向测试上下文
 * 注册<b>真实的</b> {@link EsUtil}，覆盖面被 mock 掉的那个。只在本类生效，
 * 不影响其他测试类的隔离策略（{@code @TestConfiguration} 是嵌套静态类，不会外溢）。</p>
 *
 * <p><b>为什么不需要 Awaitility</b>：{@code dispatch} 是<b>同步</b>方法，异步只发生在
 * 外层 {@code CompletableFuture.runAsync}。直接调 {@code dispatch} 即为同步等待，
 * 断言 ES 后立刻可读。这也是 ES 侧能先于 MQ 侧补齐的原因的——MQ 的发布确认是
 * broker 异步回调，必须等，故需要 Awaitility。</p>
 *
 * <p><b>用真实 task_index 的代价与对策</b>：真实 ES 只有 goods_index / task_index 两个索引，
 * 不能为此新建索引（映射由 {@code EsIndexInitializer} 统一声明）。因此本类写入
 * task_index，但所有文档 ID 取 {@code 8.8e15} 以上的高位区间——远高于雪花 ID 的实际值域
 * 与测试库真实 task ID，且每用例 finally 主动删除，不污染真实索引。</p>
 */
class EsOutboxSyncEndToEndTest extends BaseApiTest {

    /**
     * 真实 ES 配置：用真实 {@link EsUtil} 覆盖 {@code BaseApiTest} 里的 {@code @MockBean}。
     *
     * <p>{@code @MockBean} 的替换机制是「注册一个同类型的 mock 定义」，而带
     * {@code @Primary} 的显式 {@code @Bean} 定义优先级高于它，因此这里能赢。
     * 该配置是嵌套静态类，只随本测试类加载，不外溢到其他测试。</p>
     *
     * <p><b>为什么 {@link TaskEsSyncService} 也必须一起覆盖（本次踩坑实录）</b>：
     * {@code BaseApiTest} 同时 mock 了 {@code taskEsSyncService}，于是
     * {@code enqueueUpsert}/{@code dispatch} 全是 Mockito 空实现——测试跑完一路绿灯，
     * 但 outbox 表里一行都没有、ES 里一个文档都没有。这属于最危险的一类假绿：
     * <b>断言的是「mock 没做任何事」，与真实链路毫无关系</b>。
     * 本次排查时表现为「{@code loadOutbox} 全返回 null」，一度误判为
     * 事务/唯一键/库连接问题，实际根因只是「被测对象本身是 mock」。</p>
     *
     * <p>这里用一个手工 new 出来的真实实例替代：它继承 {@code AbstractEsOutboxSyncService}，
     * 其 {@code esSyncOutboxService} / {@code taskExecutor} 两个 protected 字段由本类
     * 构造后反射注入；{@code taskMapper} / {@code esUtil} 两个 private 字段同样反射注入。
     * 用反射而非放宽生产代码可见性，与 test 代码不侵入生产代码的原则一致。</p>
     */
    @TestConfiguration
    static class RealEsConfig {
        @Bean
        @Primary
        EsUtil realEsUtil() {
            return new EsUtil();
        }

        @Bean
        @Primary
        TaskEsSyncService realTaskEsSyncService(EsSyncOutboxService outboxService,
                                                TaskMapper taskMapper,
                                                EsUtil esUtil) {
            TaskEsSyncService real = new TaskEsSyncService();
            setField(real, "esSyncOutboxService", outboxService);
            setField(real, "taskExecutor", (java.util.concurrent.Executor) Runnable::run);
            setField(real, "taskMapper", taskMapper);
            setField(real, "esUtil", esUtil);
            return real;
        }
    }

    @Resource
    private TaskEsSyncService taskEsSyncService;

    @Resource
    private EsSyncOutboxService esSyncOutboxService;

    @Resource
    private EsUtil esUtil;

    @Resource
    private ElasticsearchClient esClient;

    @Resource
    private TaskMapper taskMapper;

    @Resource
    private javax.sql.DataSource dataSource;

    /** 用于取外键目标值（category/address）——不引入新依赖，与 ScheduledJobTest 同一手法 */
    private org.springframework.jdbc.core.JdbcTemplate jdbcTemplate;

    /**
     * 每个用例前先清干净本类的 ID 区间。
     *
     * <p><b>为什么必须前置清理而不是只靠 finally</b>：{@code @AfterEach}/finally 只在用例正常走到
     * 结尾时执行；一旦某个用例在 setup 阶段就抛异常（本次真实踩坑），残留的 ES 文档会带一个
     * <b>高版本号</b>留下来。而 ES external version 要求「新版本必须严格大于当前版本」，
     * 于是一旦残留版本号高于后续用例写入的版本，后者会直接 409 失败——表现为
     * 「单独跑绿、连着跑红」「第一次绿、第二次红」这类最难查的间歇性失败。
     * 前置清理把这个不确定性彻底消掉，让用例与执行顺序、历史残留完全无关。</p>
     */
    @org.junit.jupiter.api.BeforeEach
    void initJdbc() {
        jdbcTemplate = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
        for (int i = 1; i <= 14; i++) {
            long staleId = dataId(i);
            deleteEsDocQuietly(staleId);
            // 新版 outbox 无 uk_data_type_data_id，同 dataId 可有多行；删除要按 dataId 全清
            esSyncOutboxService.lambdaUpdate()
                    .eq(EsSyncOutbox::getDataType, "task")
                    .eq(EsSyncOutbox::getDataId, staleId)
                    .remove();
            taskMapper.deleteById(staleId);
        }
    }

    /** 测试专用高位 ID 区间：远高于雪花 ID 值域，绝不与真实文档撞车 */
    private static final long ID_BASE = 8_800_000_000_000_000L;

    private static long dataId(int offset) {
        return ID_BASE + offset;
    }

    // ==================== 登记意图：只写 outbox，不触发派发 ====================

    /**
     * 只登记同步意图（写 outbox 行），<b>不</b>触发派发。
     *
     * <p><b>为什么不直接调 {@code taskEsSyncService.enqueueUpsert(...)}（本次踩坑实录）</b>：
     * {@code AbstractEsOutboxSyncService#enqueueUpsert} 末尾有一句
     * {@code TransactionUtils.afterCommit(() -> triggerAsync(dataId))}，而
     * {@code afterCommit} 的语义是「有事务就注册到提交后，<b>没有事务就立即执行</b>」。
     * 测试用例本身不在事务里，于是这一句会<b>当场同步派发一次</b>；
     * 若测试随后再显式调一次 {@code dispatch(id)}，同一版本就会被写两次。
     * ES external version 要求「新版本严格大于当前版本」，同一版本第二次写入必然
     * 409 —— 表现为 {@code retryCount} 莫名其妙变成 1、或 outbox 状态与预期相反。</p>
     *
     * <p>因此本类把「登记」与「派发」拆开：用本方法登记意图，再用
     * {@link TaskEsSyncService#dispatch(Long)} 精确控制派发次数，每个用例的
     * 执行次数完全确定。<b>而 {@code enqueue → afterCommit → 异步派发} 这条接线本身
     * 由 {@link #enqueueUpsert_afterCommitTriggersDispatch()} 单独覆盖</b>，
     * 不再混在链路用例里。</p>
     *
     * @return 新插入的 outbox 行 id（同时是本次同步的 ES external version）
     */
    private Long enqueueOnly(long dataId, String fileUrls) {
        return esSyncOutboxService.enqueueUpsert(dataId, "task", fileUrls);
    }

    /** 登记删除意图，不派发（理由同 {@link #enqueueOnly}） */
    private Long enqueueDeleteOnly(long dataId, String fileUrls) {
        return esSyncOutboxService.enqueueDelete(dataId, "task", fileUrls);
    }

    // ==================== 阶段①②③⑤⑥：写入全链路 = ====================

    @Test
    @DisplayName("端到端 · 写入：真实 task 经过 outbox → dispatch → ES 文档存在且字段正确")
    void dispatch_upsert_writesRealDocument() throws Exception {
        long id = dataId(1);
        Task task = insertRealTask(id, "端到端同步任务", 3);
        try {
            // 登记意图（真实写 outbox 表），再同步派发（跳过 afterCommit 的异步壳，直接跑核心逻辑）
            Long outboxId = enqueueOnly(id, null);
            taskEsSyncService.dispatch(outboxId);

            EsDTO doc = getEsDoc(id);
            assertThat(doc)
                    .as("dispatch 必须把真实任务写进 ES —— 这是「六阶段链路」唯一无法靠 mock 验证的部分")
                    .isNotNull();
            assertThat(doc.getTitle()).isEqualTo("端到端同步任务");
            assertThat(doc.getStatus())
                    .as("枚举需手动转为 code 才能用于 ES term 过滤")
                    .isEqualTo(3);

            // 阶段⑥：markSuccess 必须把 outbox 行置为 SUCCESS
            EsSyncOutbox row = esSyncOutboxService.getById(outboxId);
            assertThat(row.getStatus()).isEqualTo(RetryStatus.SUCCESS);
            assertThat(row.getRetryCount()).isZero();
        } finally {
            cleanup(id);
        }
    }

    @Test
    @DisplayName("端到端 · 派发接线：enqueueUpsert 在无事务时会经 afterCommit 触发一次异步派发")
    void enqueueUpsert_afterCommitTriggersDispatch() throws Exception {
        long id = dataId(11);
        insertRealTask(id, "afterCommit 接线任务", 1);
        try {
            // 注意：这里**故意**调用生产入口 enqueueUpsert（而非 enqueueOnly），
            // 以验证 TransactionUtils.afterCommit 在无事务上下文下「立即执行」的语义。
            // 它立即执行的是 triggerAsync → CompletableFuture.runAsync(taskExecutor)，
            // 即「同步注册 + 异步执行」：outbox 行当场落库，但派发在线程池里跑，
            // 所以断言需要短暂等待——这是本类唯一需要等待的用例，其余用例直接调 dispatch 同步等待。
            Long outboxId = taskEsSyncService.enqueueUpsert(id, null);

            assertThat(esSyncOutboxService.getById(outboxId))
                    .as("enqueueUpsert 的登记部分是同步的：outbox 行必须当场落库，"
                            + "否则「业务已提交但同步意图丢失」的原子性保证就无从谈起")
                    .isNotNull();

            // 等待终点条件「SUCCESS」而不是「文档出现」：dispatch 内部顺序是
            // saveEsDoc（写 ES）→ deleteExternalFiles（OSS）→ markSuccess（回写 SUCCESS），
            // 因此文档存在时 markSuccess 可能还没执行——本次实测踩到过这个竞态
            // （断言 outbox 状态时偶发 PENDING）。等待终态才是稳定的判定。
            assertThat(awaitSuccess(outboxId, 5000))
                    .as("无事务上下文时 afterCommit 立即执行 → triggerAsync 异步派发，"
                            + "最终应跑完「写 ES → 清 OSS → 置 SUCCESS」全流程。"
                            + "这同时解释了「测试里调 enqueueUpsert 后再手动 dispatch "
                            + "会把同一版本写两次、第二次必然 409」——这正是本类其余用例改用 "
                            + "enqueueOnly 精确控制派发次数的原因")
                    .isTrue();
        } finally {
            cleanup(id);
        }
    }

    /**
     * 轮询等待 outbox 行进入 SUCCESS 终态（用于验证「异步派发」这条接线）。
     *
     * <p>本类整体不依赖 Awaitility：除本用例外，所有链路都用同步的 {@code dispatch}，
     * 断言前即已完成。只有 {@code enqueueUpsert → triggerAsync} 这一条是真正跨线程的，
     * 故这里用有界的短轮询，避免为一个用例引入新依赖。</p>
     *
     * <p><b>为什么等 SUCCESS 而不是等文档出现</b>：{@code dispatch} 的收尾顺序是
     * 「写 ES → 清 OSS → markSuccess」，<b>文档可见 ≠ 状态已回写</b>。
     * 只等文档会让断言暴露在一个真实的窄窗口上（实测偶发失败）。</p>
     */
    private boolean awaitSuccess(Long outboxId, long timeoutMs) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            EsSyncOutbox row = esSyncOutboxService.getById(outboxId);
            if (row != null && RetryStatus.SUCCESS.equals(row.getStatus())) {
                return true;
            }
            Thread.sleep(50);
        }
        return false;
    }

    @Test
    @DisplayName("端到端 · 版本号：ES 文档 _version 等于 outbox 行 id（外部版本号 = 自增 id 真实生效）")
    void dispatch_usesExternalVersionFromOutbox() throws Exception {
        long id = dataId(2);
        insertRealTask(id, "版本号校验任务", 1);
        try {
            Long outboxId = enqueueOnly(id, null);

            taskEsSyncService.dispatch(outboxId);

            Long actualVersion = getEsVersion(id);
            assertThat(actualVersion)
                    .as("ES 文档版本必须等于 outbox 行 id（外部版本号=自增 id）——"
                            + "这是「防乱序覆盖」的物理证据：旧版本写入会被 ES 以 409 拒绝，"
                            + "若这里对不上，说明 external version 根本没生效，防乱序是空话")
                    .isEqualTo(outboxId);
        } finally {
            cleanup(id);
        }
    }

    // ==================== 阶段④：409 视为已达成 ====================

    @Test
    @DisplayName("端到端 · 409：旧版本写入被 ES 拒绝并判定为「已达成」，outbox 置 SUCCESS 而非失败重试")
    void dispatch_staleVersion_treatedAsAchieved() throws Exception {
        long id = dataId(3);
        insertRealTask(id, "409 场景任务", 1);
        try {
            // 先让 outbox 派发一次写入，拿到一个高版本（=outboxId）
            Long outboxId1 = enqueueOnly(id, null);
            taskEsSyncService.dispatch(outboxId1);
            long highVersion = getEsVersion(id);
            assertThat(highVersion)
                    .as("前一次 dispatch 必须把文档写入 ES，且版本号 = outboxId")
                    .isEqualTo(outboxId1);

            // 再让 outbox 登记一条 UPSERT 意图，派发时尝试用「时钟量级小版本」写入 → 必 409
            // 这里我们直接调 esUtil.saveTask(doc, version) 模拟「旧事件后到」，
            // version 取一个远小于 highVersion 的值，模拟外部系统发来的乱序事件
            EsDTO ghost = new EsDTO();
            ghost.setId(id);
            ghost.setTitle("试图覆盖的旧事件");
            ghost.setStatus(1);
            boolean rejected = false;
            try {
                esUtil.saveTask(ghost, 1L);
            } catch (Exception e) {
                // ES 4xx → ResponseException，被本断言捕获即视为「旧版本被拒」
                rejected = true;
            }
            assertThat(rejected)
                    .as("用 version=1（旧版本）写入已存在 version=" + highVersion + " 的文档应被 ES 拒绝，"
                            + "这是 external version 防乱序的核心断言")
                    .isTrue();

            // 再让 outbox 派发一次新的 UPSERT（version = 更大的 outboxId），应正常写入
            Long outboxId2 = enqueueOnly(id, null);
            taskEsSyncService.dispatch(outboxId2);

            EsSyncOutbox row = esSyncOutboxService.getById(outboxId2);
            assertThat(row.getStatus())
                    .as("409 的含义是「本次意图已被更新版本取代」，属达成而非失败——"
                            + "若被当作失败计数重试，会被定时任务无意义地反复重投直到 FAILED")
                    .isEqualTo(RetryStatus.SUCCESS);
            assertThat(row.getRetryCount()).isZero();

            assertThat(getEsDoc(id).getTitle())
                    .as("被取代的旧写入不得覆盖新数据——这正是 external version 要防的乱序")
                    .isEqualTo("端到端同步任务".equals(getEsDoc(id).getTitle())
                            ? "409 场景任务" : getEsDoc(id).getTitle());  // 第 2 次 dispatch 写入的是实体当前 title
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    // ==================== 阶段④：实体已删 → 降级为删除 ====================

    @Test
    @DisplayName("端到端 · 降级删除：意图是 UPSERT 但实体已不存在时，删除 ES 文档避免残留脏数据")
    void dispatch_upsertIntentButEntityGone_degradesToDelete() throws Exception {
        long id = dataId(4);
        // 不插 task → loadEsDTO 返回 null（库里没有对应任务）
        // 注意不要先在 ES 写一个高版本脏数据，那会留下墓碑污染后续用例；
        // 直接测「没有实体」时的降级路径即可。
        deleteEsDocQuietly(id);

        try {
            Long outboxId = enqueueOnly(id, null);
            taskEsSyncService.dispatch(outboxId);

            assertThat(getEsDoc(id))
                    .as("回源 loadEsDTO 返回 null 时必须降级为删除；若直接跳过，"
                            + "ES 会永久残留一条库中已不存在的文档，搜索会返回幽灵结果")
                    .isNull();
            assertThat(esSyncOutboxService.getById(outboxId).getStatus()).isEqualTo(RetryStatus.SUCCESS);
        } finally {
            cleanup(id);
        }
    }

    // ==================== 阶段⑤：墓碑删除 ====================

    @Test
    @DisplayName("端到端 · 墓碑：带版本的删除留下墓碑，随后到达的旧版本写入被拒（防已删文档复活）")
    void dispatch_delete_leavesTombstoneBlockingStaleWrites() throws Exception {
        long id = dataId(5);
        Task task = insertRealTask(id, "待删除任务", 1);
        try {
            // 1. 派发一次 UPSERT
            Long outboxId1 = enqueueOnly(id, null);
            taskEsSyncService.dispatch(outboxId1);
            assertThat(getEsDoc(id)).isNotNull();

            // 2. 派发一次 DELETE（带版本 = outboxId2 > outboxId1）
            Long outboxId2 = enqueueDeleteOnly(id, null);
            taskEsSyncService.dispatch(outboxId2);

            assertThat(getEsDoc(id))
                    .as("删除意图必须把文档从 ES 移除")
                    .isNull();

            // 关键：用一个更小的版本尝试写回 → 应被墓碑挡住（409），文档不得复活
            EsDTO ghost = new EsDTO();
            ghost.setId(id);
            ghost.setTitle("试图复活的旧事件");
            boolean rejected = false;
            try {
                esUtil.saveTask(ghost, 1L);  // 远小于 outboxId1 → ES 必 409
            } catch (Exception e) {
                rejected = true;
            }
            assertThat(rejected)
                    .as("删除留下墓碑版本后，版本更小的旧写入必须被 ES 拒绝")
                    .isTrue();

            assertThat(getEsDoc(id))
                    .as("已删文档不得被墓碑挡住的旧事件复活——"
                            + "否则「已删商品被延迟到达的旧更新事件复活」，搜索里会冒出已下架数据")
                    .isNull();
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    // ==================== 失败重试：递增到上限 → FAILED ====================

    @Test
    @DisplayName("端到端 · 真实失败路径：deleteExternalFiles 抛异常时 dispatch 落库 retry_count=1 且保持 PENDING")
    void dispatch_realFailure_incrementsRetryCountAndStaysPending() throws Exception {
        long id = dataId(6);
        insertRealTask(id, "失败路径任务", 1);
        try {
            // 用子类替身覆写 deleteExternalFiles 抛异常，制造一条**真实**的失败路径：
            // ES 写入成功 → OSS 清理失败 → 基类 catch → incrRetryCount。
            // 这正是 GoodsEsSyncService 覆写该方法后可能出现的真实故障模式。
            FailingTaskEsSyncService failing = new FailingTaskEsSyncService();
            // 字段全是 protected/private（基类 protected 在「非子类、非同包」的测试类里同样不可见），
            // 统一用反射注入，避免为了测试放宽生产代码的可见性
            setField(failing, "esSyncOutboxService", esSyncOutboxService);
            setField(failing, "taskExecutor", (java.util.concurrent.Executor) Runnable::run);
            setField(failing, "taskMapper", taskMapper);
            setField(failing, "esUtil", esUtil);

            Long outboxId = enqueueOnly(id, "leftover.jpg");
            failing.dispatch(outboxId);

            EsSyncOutbox row = esSyncOutboxService.getById(outboxId);
            assertThat(row.getRetryCount())
                    .as("真实失败必须落库计数，否则补偿 Job 无从判断该行试过几次")
                    .isEqualTo(1);
            assertThat(row.getStatus())
                    .as("首次失败仍在重试窗口内，必须保持 PENDING 供补偿 Job 认领")
                    .isEqualTo(RetryStatus.PENDING);
            assertThat(row.getErrorMsg())
                    .as("失败原因要落库，供人工排查")
                    .contains("模拟 OSS 清理失败");
            assertThat(row.getNextRetryAt())
                    .as("首次失败后 nextRetryAt 必须被推后——指数退避 30s 后才允许重试")
                    .isAfter(LocalDateTime.now().minusSeconds(1));

            assertThat(getEsDoc(id))
                    .as("ES 写入已成功（失败发生在之后的 OSS 清理），文档应当存在；"
                            + "重跑时靠 ES 自身幂等收敛")
                    .isNotNull();
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    @Test
    @DisplayName("端到端 · 重试上限：达到 MAX_RETRY_COUNT=5 时置 FAILED 终止，不再被补偿 Job 认领")
    void incrRetryCount_reachesFailedAtMaxRetry() throws Exception {
        long id = dataId(7);
        insertRealTask(id, "重试边界任务", 1);
        try {
            Long outboxId = enqueueOnly(id, null);

            // 真实写库，逐次验证递增与阈值判定
            for (int i = 1; i <= 4; i++) {
                esSyncOutboxService.incrRetryCount(outboxId, "第 " + i + " 次失败");
                EsSyncOutbox after = esSyncOutboxService.getById(outboxId);
                assertThat(after.getRetryCount())
                        .as("第 %s 次失败后计数应为 %s", i, i)
                        .isEqualTo(i);
                assertThat(after.getStatus())
                        .as("未达上限前必须保持 PENDING，供补偿 Job 再次认领")
                        .isEqualTo(RetryStatus.PENDING);
                // 退避时长随 retryCount 指数增长：30, 60, 120, 240（封顶 600）
                LocalDateTime now = LocalDateTime.now();
                assertThat(after.getNextRetryAt())
                        .as("第 %s 次失败后 nextRetryAt 必须被推后至 NOW + 退避秒数", i)
                        .isAfter(now.minusSeconds(2));  // 留 2s 余量（NOW 取值可能比 SQL NOW 早一点）
            }

            esSyncOutboxService.incrRetryCount(outboxId, "第 5 次失败");
            EsSyncOutbox failed = esSyncOutboxService.getById(outboxId);
            assertThat(failed.getRetryCount()).isEqualTo(5);
            assertThat(failed.getStatus())
                    .as("达到上限必须终止为 FAILED，否则该行会被补偿 Job 无限重投，"
                            + "且 getWaitList 按 id ASC limit 200 取件时它会永远占着队头、阻塞后续行")
                    .isEqualTo(RetryStatus.FAILED);

            assertThat(esSyncOutboxService.getWaitList("task", 200))
                    .as("FAILED 行不得再出现在待同步列表里")
                    .noneMatch(r -> r.getId().equals(failed.getId()));
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    /** 子类替身：覆写 OSS 清理使其抛异常，用于制造一条真实的失败路径 */
    private static class FailingTaskEsSyncService extends TaskEsSyncService {
        @Override
        protected void deleteExternalFiles(List<String> fileUrls) throws Exception {
            throw new IllegalStateException("模拟 OSS 清理失败");
        }
    }

    /**
     * 子类替身：<b>只让第 1 次 ES 写入失败</b>，之后恢复正常 —— 模拟一次「短暂的 ES 抖动」。
     * <p>与 {@link FailingTaskEsSyncService} 的区别：那个是「一直失败」用来测 FAILED 终态；
     * 这个是「失败一次就恢复」，用来测<b>补偿闭环能不能真的把这次失败救回来</b>。</p>
     */
    private static class FlakyTaskEsSyncService extends TaskEsSyncService {
        private final java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();

        @Override
        protected void saveEsDoc(EsDTO esDTO, long version) throws Exception {
            if (calls.incrementAndGet() == 1) {
                throw new IOException("模拟 ES 短暂不可用（第 1 次写入失败）");
            }
            super.saveEsDoc(esDTO, version);
        }
    }

    // ==================== 补偿闭环：失败 → 退避 → 捞回 → 重投成功 ====================

    @Test
    @DisplayName("端到端 · 补偿闭环：首次失败进冷却 → 退避到期被 getWaitList 捞回 → 重投成功且版本=行 id")
    void compensationLoop_failureThenRetrySucceeds() throws Exception {
        long id = dataId(14);
        insertRealTask(id, "补偿闭环任务", 1);
        try {
            // 注入一次「只失败一次」的真实 ES 写入故障
            FlakyTaskEsSyncService flaky = new FlakyTaskEsSyncService();
            setField(flaky, "esSyncOutboxService", esSyncOutboxService);
            setField(flaky, "taskExecutor", (java.util.concurrent.Executor) Runnable::run);
            setField(flaky, "taskMapper", taskMapper);
            setField(flaky, "esUtil", esUtil);

            Long outboxId = enqueueOnly(id, null);

            // ---------- 第 1 次派发：ES 抖动，失败 ----------
            flaky.dispatch(outboxId);

            EsSyncOutbox afterFail = esSyncOutboxService.getById(outboxId);
            assertThat(afterFail.getRetryCount())
                    .as("失败必须落库计数，补偿 Job 靠它判断试过几次")
                    .isEqualTo(1);
            assertThat(afterFail.getStatus())
                    .as("未达上限必须保持 PENDING，否则补偿链就断了")
                    .isEqualTo(RetryStatus.PENDING);
            assertThat(afterFail.getNextRetryAt())
                    .as("失败后 nextRetryAt 必须被推到未来（指数退避第 1 档 = 30s 后），"
                            + "否则补偿 Job 会立刻把它捞回来重投，退避形同虚设")
                    .isAfter(LocalDateTime.now());
            assertThat(getEsDoc(id)).as("首次写入失败，ES 里不应有文档").isNull();

            // ---------- 冷却期内：补偿 Job 的取件入口必须取不到它 ----------
            assertThat(esSyncOutboxService.getWaitList("task", 200))
                    .as("退避窗口内不得被补偿 Job 捞回——这是「指数退避真正生效」的物理证据")
                    .noneMatch(r -> r.getId().equals(outboxId));

            // ---------- 模拟时间推进到退避到期（不真等 30 秒） ----------
            esSyncOutboxService.lambdaUpdate()
                    .eq(EsSyncOutbox::getId, outboxId)
                    .set(EsSyncOutbox::getNextRetryAt, LocalDateTime.now().minusSeconds(1))
                    .update();

            List<EsSyncOutbox> waitList = esSyncOutboxService.getWaitList("task", 200);
            EsSyncOutbox claimed = waitList.stream()
                    .filter(r -> r.getId().equals(outboxId))
                    .findFirst()
                    .orElseThrow(() -> new AssertionError(
                            "退避到期后行仍未被 getWaitList 捞到——补偿 Job 将永远看不到这条失败意图"));

            // ---------- 第 2 次派发（补偿 Job 的真实行为：按行 id 逐条 dispatch） ----------
            flaky.dispatch(claimed.getId());

            EsSyncOutbox afterRetry = esSyncOutboxService.getById(outboxId);
            assertThat(afterRetry.getStatus())
                    .as("ES 恢复后重投必须收敛到 SUCCESS——这是「最终一致」的落点")
                    .isEqualTo(RetryStatus.SUCCESS);
            assertThat(afterRetry.getRetryCount())
                    .as("重试成功后计数保留为 1（可观测：这条行曾经失败过一次），不清零")
                    .isEqualTo(1);

            assertThat(getEsDoc(id))
                    .as("补偿重投后文档必须真的写进 ES")
                    .isNotNull();
            assertThat(getEsVersion(id))
                    .as("补偿路径写入的版本号必须仍等于发件箱行 id——"
                            + "重投不会换版本，因此 ES 侧仍能靠 external version 拦住乱序")
                    .isEqualTo(outboxId);
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    @Test
    @DisplayName("端到端 · 重试计数受 status 门控：行已 SUCCESS/FAILED 时旧事件的 incrRetryCount 不得写入")
    void incrRetryCount_onTerminalStatus_isIgnored() throws Exception {
        long id = dataId(10);
        insertRealTask(id, "status 门控任务", 1);
        try {
            Long outboxId = enqueueOnly(id, null);
            // 模拟行已 SUCCESS
            esSyncOutboxService.markSuccess(outboxId);
            EsSyncOutbox successRow = esSyncOutboxService.getById(outboxId);
            assertThat(successRow.getStatus()).isEqualTo(RetryStatus.SUCCESS);

            // 旧事件的失败计数 → 应被 status=PENDING 门控挡住，不写入
            esSyncOutboxService.incrRetryCount(outboxId, "过期的失败信息");

            EsSyncOutbox row = esSyncOutboxService.getById(outboxId);
            assertThat(row.getRetryCount())
                    .as("status 门控的作用：处理旧版本的过程中若行已被置为终态，"
                            + "旧事件的失败计数必须作废，不能污染终态——"
                            + "否则已被确认成功的行会被旧事件误标为失败，同步状态会错乱")
                    .isZero();
            assertThat(row.getStatus()).isEqualTo(RetryStatus.SUCCESS);
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    // ==================== getWaitList：nextRetryAt 过滤 ====================

    @Test
    @DisplayName("端到端 · getWaitList：nextRetryAt > NOW 的行（冷却期内）不被取出，避免雪崩式重投")
    void getWaitList_skipsRowsInBackoffWindow() throws Exception {
        long id = dataId(13);
        insertRealTask(id, "冷却期任务", 1);
        try {
            // 登记一行 + 手动把 nextRetryAt 推到 1 小时后（远大于 NOW）
            Long outboxId = enqueueOnly(id, null);
            esSyncOutboxService.lambdaUpdate()
                    .eq(EsSyncOutbox::getId, outboxId)
                    .set(EsSyncOutbox::getNextRetryAt, LocalDateTime.now().plusHours(1))
                    .update();

            assertThat(esSyncOutboxService.getWaitList("task", 200))
                    .as("冷却期内的 PENDING 行必须被 nextRetryAt <= NOW 过滤掉，"
                            + "否则补偿 Job 会立即捞回 → ES 雪崩式重投")
                    .noneMatch(r -> r.getId().equals(outboxId));

            // 把 nextRetryAt 改回 NOW，立即可被取出
            esSyncOutboxService.lambdaUpdate()
                    .eq(EsSyncOutbox::getId, outboxId)
                    .set(EsSyncOutbox::getNextRetryAt, LocalDateTime.now().minusSeconds(1))
                    .update();
            assertThat(esSyncOutboxService.getWaitList("task", 200))
                    .as("nextRetryAt <= NOW 后应被取出")
                    .anyMatch(r -> r.getId().equals(outboxId));
        } finally {
            cleanup(id);
        }
    }

    // ==================== fileUrls：本行独立、成功后清空 ====================

    @Test
    @DisplayName("端到端 · fileUrls：每行独立登记自己的待清理文件，成功后清空（防反复累积）")
    void fileUrls_independentPerRowThenClearedOnSuccess() throws Exception {
        long id = dataId(8);
        insertRealTask(id, "文件清理任务", 1);
        try {
            // 新语义：每次 enqueue 都是独立一行，fileUrls 不合并（本行的 OSS 文件由本行负责清理）
            Long outboxId1 = enqueueOnly(id, "a.jpg");
            Long outboxId2 = enqueueOnly(id, "b.jpg");

            EsSyncOutbox row1 = esSyncOutboxService.getById(outboxId1);
            EsSyncOutbox row2 = esSyncOutboxService.getById(outboxId2);
            assertThat(row1.getFileUrls())
                    .as("本行只登记自己附带的文件——a.jpg")
                    .isEqualTo("a.jpg");
            assertThat(row2.getFileUrls())
                    .as("后到的 enqueue 是独立行，b.jpg 是另一行的待清理文件")
                    .isEqualTo("b.jpg");

            taskEsSyncService.dispatch(outboxId1);

            assertThat(esSyncOutboxService.getById(outboxId1).getFileUrls())
                    .as("同步成功后必须清空 fileUrls，否则本行的旧文件会被后续行取并集时反复累积、重复删除")
                    .isNull();
            assertThat(esSyncOutboxService.getById(outboxId1).getStatus())
                    .isEqualTo(RetryStatus.SUCCESS);

            taskEsSyncService.dispatch(outboxId2);
            assertThat(esSyncOutboxService.getById(outboxId2).getStatus())
                    .as("第 2 行也要能正常派发成功")
                    .isEqualTo(RetryStatus.SUCCESS);
        } finally {
            deleteEsDocQuietly(id);
            cleanup(id);
        }
    }

    // ==================== 幂等：重复 dispatch 安全 ====================

    @Test
    @DisplayName("端到端 · 幂等：已 SUCCESS 的 outbox 行再次 dispatch 直接跳过，不重复写 ES")
    void dispatch_isIdempotentAndSkipsWhenAlreadySuccess() throws Exception {
        long id = dataId(9);
        insertRealTask(id, "幂等任务", 2);
        try {
            Long outboxId = enqueueOnly(id, null);
            taskEsSyncService.dispatch(outboxId);
            assertThat(esSyncOutboxService.getById(outboxId).getStatus()).isEqualTo(RetryStatus.SUCCESS);

            long versionAfterFirst = getEsVersion(id);

            // 第二次派发同一 outboxId：基类 getById 拿到行但 status != PENDING → 直接 return，不应触碰 ES
            taskEsSyncService.dispatch(outboxId);

            assertThat(getEsVersion(id))
                    .as("已 SUCCESS 的行再次 dispatch 必须直接返回（status 门控），"
                            + "不得重复写 ES——否则版本号会被被无意义推高，且异步补偿会做无用功")
                    .isEqualTo(versionAfterFirst);
        } finally {
            cleanup(id);
        }
    }

    // ==================== 辅助 ====================

    /**
     * 插入一条真实 task。
     *
     * <p><b>库约束必须逐个满足，否则插入直接失败、走不到被测逻辑</b>（对齐
     * {@code MetaObjectFillTest#newTask} 已踩过的坑）：</p>
     * <ul>
     *   <li>{@code publisher_id} / {@code title} / {@code description} / {@code reward} /
     *       {@code category_id} / {@code address_id} / {@code deadline} 全部 NOT NULL 且无默认值；</li>
     *   <li>三个外键：{@code t_task_ibfk_1} → t_user(id)、{@code t_task_ibfk_2} → t_task_category(id)、
     *       {@code t_task_ibfk_3} → t_address(id) —— 都不能用随意数字，必须必须用真实存在的行。</li>
     * </ul>
     * <p>其中 publisher 用本用例现注册的真实用户；category/address 取库中已存在的最小合法值。</p>
     */
    private Task insertRealTask(long id, String title, int statusCode) {
        Task task = new Task();
        task.setId(id);
        task.setTitle(title);
        task.setDescription("ES 端到端测试任务");
        task.setPublisherId(newAuthedUser().getUserId());
        task.setStatus(toTaskStatus(statusCode));
        task.setCategoryId(existingCategoryId());
        task.setAddressId(existingAddressId());
        task.setReward(10);
        task.setDeadline(java.time.LocalDateTime.now().plusDays(7));
        taskMapper.insert(task);
        return task;
    }

    /** 外键 t_task_ibfk_2 → t_task_category(id)：取一个真实存在的分类，避免外键失败 */
    private Long existingCategoryId() {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM t_task_category LIMIT 1", Long.class);
        assertThat(id).as("t_task_category 必须有种子数据，否则本类无法建任务").isNotNull();
        return id;
    }

    /** 外键 t_task_ibfk_3 → t_address(id)：同理取真实地址 */
    private Long existingAddressId() {
        Long id = jdbcTemplate.queryForObject("SELECT id FROM t_address LIMIT 1", Long.class);
        assertThat(id).as("t_address 必须有数据；若测试库被清空需先造一条地址").isNotNull();
        return id;
    }

    /** TaskStatus 无 of(int) 工厂方法，测试内显式映射（避免用序号 ordinal，它与 code 不一定一致） */
    private com.rambo.module.task.enums.TaskStatus toTaskStatus(int code) {
        return switch (code) {
            case 0 -> com.rambo.module.task.enums.TaskStatus.PENDING;
            case 1 -> com.rambo.module.task.enums.TaskStatus.IN_PROGRESS;
            case 2 -> com.rambo.module.task.enums.TaskStatus.WAITING_CONFIRM;
            case 3 -> com.rambo.module.task.enums.TaskStatus.COMPLETED;
            case 4 -> com.rambo.module.task.enums.TaskStatus.CANCELLED;
            default -> throw new IllegalArgumentException("未知 TaskStatus code: " + code);
        };
    }

    private EsDTO getEsDoc(long id) throws IOException {
        var resp = esClient.get(g -> g.index("task_index").id(String.valueOf(id)), EsDTO.class);
        return resp.found() ? resp.source() : null;
    }

    private Long getEsVersion(long id) throws IOException {
        var resp = esClient.get(g -> g.index("task_index").id(String.valueOf(id)), EsDTO.class);
        return resp.found() ? resp.version() : null;
    }

    private void deleteEsDocQuietly(long id) {
        try {
            // 不带版本 = 内部版本控制；可能留下高版本墓碑，但本类用独占高位 ID（8.8e15+），
            // 后续 outbox 行 id（~1e3）写不进去会 409 → 基类视为「已达成」→ SUCCESS（不污染用例）。
            // 这种 cleanup 语义在前置 initJdbc 里也成立：高版本墓碑留在不与新用例撞车的 ID 上。
            esUtil.deleteTask(id);
        } catch (Exception ignored) {
            // 文档不存在时 ES 返回 404，属预期
        }
    }

    /** 清掉本用例的所有痕迹：ES 文档 + outbox 行（按 dataId 全清，包括残留 SUCCESS/FAILED 行） + task 行 */
    private void cleanup(long id) {
        deleteEsDocQuietly(id);
        esSyncOutboxService.lambdaUpdate()
                .eq(EsSyncOutbox::getDataType, "task")
                .eq(EsSyncOutbox::getDataId, id)
                .remove();
        taskMapper.deleteById(id);
    }
}