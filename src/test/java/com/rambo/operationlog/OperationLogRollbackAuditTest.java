package com.rambo.operationlog;

import com.rambo.BaseApiTest;
import com.rambo.common.exception.BusinessException;
import com.rambo.module.operationlog.annotation.Log;
import com.rambo.module.operationlog.enums.OperationActionEnum;
import com.rambo.module.operationlog.enums.OperationModuleEnum;
import com.rambo.module.operationlog.enums.OperationTargetTypeEnum;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 操作审计与事务一致性测试（对应待修清单 P1-6）。
 *
 * <p>P1-6 的修复引入了「审计记录必须反映事务最终成败」的机制：业务方法自身成功、
 * 但它被外层事务包裹且外层最终回滚时，审计需改写为失败记录
 * （{@code TransactionUtils.afterCompletion} + {@code LogAspect} 分流）。
 * 该分支在修复当时只有代码正确性、缺少运行期证据——本测试把它闭环。</p>
 *
 * <p><b>为什么用探针服务而不是真实业务方法：</b>触发该分支的条件是
 * 「内层 {@code @Log} 方法成功 + 外层事务最终回滚」。若沿用真实接口，需要构造
 * 「外层已成功执行大半、最后一步才失败」的数据（例如管理员下架商品时第 3 个订单取消失败），
 * 前置成本高且随业务演进而脆弱。探针服务经过真实 Spring AOP 织入、跑真实事务、落真实库，
 * 覆盖的正是被测机制本身，且不随业务重构失效。</p>
 *
 * <p>三个用例构成完整对照：回滚 → 改写为失败；提交 → 保持成功；业务自身抛异常 → 立即记失败。
 * 缺任何一个都无法排除「无条件改写」或「压根没落库」这类错误实现。</p>
 *
 * <p><b>运行后可复核：</b>用例只清理自己那一条记录，因此跑完三次后会留下三条
 * {@code description LIKE 'P1-6-探针-%'} 的记录，可直接查库核对——
 * 其中「外层回滚」那条是 P1-6 改写分支唯一的运行期证据。</p>
 */
@Import(OperationLogRollbackAuditTest.AuditProbeConfig.class)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class OperationLogRollbackAuditTest extends BaseApiTest {

    private static final String DESC_ROLLBACK = "P1-6-探针-外层回滚";
    private static final String DESC_COMMIT = "P1-6-探针-外层提交";
    private static final String DESC_THROW = "P1-6-探针-业务异常";
    /** 必须与 LogAspect 中的改写文案逐字一致——断言的就是那句话本身 */
    private static final String REWRITE_MSG = "外层事务回滚，本次业务变更未生效";
    /** 异步落库等待上限；saveLog 标注 @Async，落库不在调用线程内完成 */
    private static final long AWAIT_TIMEOUT_MS = 10_000L;
    /** 首次查到记录后再静默观察的时长，用于暴露「本该只有一条」的重复写入 */
    private static final long SETTLE_MS = 300L;

    @Resource
    private DataSource dataSource;
    @Resource
    private PlatformTransactionManager transactionManager;
    @Resource
    private AuditProbeService auditProbeService;

    private JdbcTemplate jdbc;
    private TransactionTemplate tx;

    @BeforeEach
    void prepareProbe() {
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(transactionManager);
    }

    @Test
    @Order(1)
    @DisplayName("内层方法成功 + 外层事务回滚 → 审计改写为失败（P1-6 核心）")
    void outerRollback_shouldRewriteAuditToFailed() {
        cleanPreviousRun(DESC_ROLLBACK);
        int callsBefore = AuditProbeService.SUCCESS_CALLS.get();

        tx.executeWithoutResult(status -> {
            auditProbeService.succeedForRollbackCase();
            // 模拟「外层事务随后回滚」：业务逻辑已执行完毕，但整体不生效
            status.setRollbackOnly();
        });

        assertThat(AuditProbeService.SUCCESS_CALLS.get())
                .as("探针业务方法必须真的执行过，否则本用例退化为「什么都没发生」")
                .isEqualTo(callsBefore + 1);

        List<AuditRow> rows = awaitAuditRows(DESC_ROLLBACK);

        assertThat(rows)
                .as("外层事务回滚后审计记录仍须落库——它是事后复盘业务失败的唯一依据")
                .hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.result())
                .as("业务方法自身成功但外层事务回滚 → 审计必须记为失败，否则留下「业务已回滚、审计却报成功」的假记录")
                .isEqualTo(1);
        assertThat(row.errorMsg())
                .as("失败原因须写明是外层回滚，与业务自身异常区分开")
                .isEqualTo(REWRITE_MSG);
    }

    @Test
    @Order(2)
    @DisplayName("内层方法成功 + 外层事务提交 → 审计保持成功（对照，排除无条件改写）")
    void outerCommit_shouldKeepAuditSuccess() {
        cleanPreviousRun(DESC_COMMIT);

        tx.executeWithoutResult(status -> auditProbeService.succeedForCommitCase());

        List<AuditRow> rows = awaitAuditRows(DESC_COMMIT);

        assertThat(rows).as("事务正常提交，审计记录必须落库且只落一条").hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.result())
                .as("事务正常提交时不得被误改写为失败——本用例与回滚用例互为对照")
                .isEqualTo(0);
        assertThat(row.errorMsg()).as("成功记录不应带失败原因").isNullOrEmpty();
    }

    @Test
    @Order(3)
    @DisplayName("业务方法自身抛异常 → 立即记失败，原因取业务异常原文")
    void businessThrows_shouldRecordFailureImmediately() {
        cleanPreviousRun(DESC_THROW);

        assertThatThrownBy(() -> tx.executeWithoutResult(
                status -> auditProbeService.throwBusinessFailure()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("审计探针模拟业务失败");

        List<AuditRow> rows = awaitAuditRows(DESC_THROW);

        assertThat(rows)
                .as("业务异常分支的结论在抛异常瞬间已确定，不必等事务结果，但记录必须落库")
                .hasSize(1);
        AuditRow row = rows.get(0);
        assertThat(row.result()).isEqualTo(1);
        assertThat(row.errorMsg())
                .as("此分支须保留业务异常原文，而非被「外层事务回滚」的改写文案覆盖")
                .contains("审计探针模拟业务失败");
    }

    /**
     * 只清理本用例自己那一条记录，不动其它用例的。
     *
     * <p>两点考虑：① 断言必须只反映本次运行的结果，历史残留会让「查到了」变得廉价；
     * ② 又不能整表清空——审计表是持久化数据，跑完应留下三条可复核的证据，
     * 尤其「外层回滚」那条，它是 P1-6 改写分支唯一的运行期证据。</p>
     */
    private void cleanPreviousRun(String description) {
        jdbc.update("DELETE FROM t_operation_log WHERE description = ?", description);
    }

    /**
     * 轮询等待异步落库，返回该 description 下已落库的全部记录。
     *
     * <p>{@code OperationLogServiceImpl.saveLog} 标注 {@code @Async("taskExecutor")}，
     * 落库发生在另一个线程，调用返回不代表记录已可查——因此必须轮询而非断言一次。</p>
     *
     * <p>首次查到后额外静默 {@value #SETTLE_MS} 毫秒再取最终结果：异步写入的顺序不保证，
     * 若只按「查到第一条」就断言，一旦切面被重复织入（或多个 advisor 命中同一方法），
     * 第二条记录可能在断言之后才落库而被漏掉——那正是 P1-5 关心的重复审计缺陷。</p>
     *
     * <p>超时返回空列表，让断言以「记录不存在」失败，而不是先抛异常掩盖真实原因。</p>
     */
    private List<AuditRow> awaitAuditRows(String description) {
        long deadline = System.currentTimeMillis() + AWAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            List<AuditRow> rows = queryRows(description);
            if (!rows.isEmpty()) {
                sleepQuietly(SETTLE_MS);
                List<AuditRow> settled = queryRows(description);
                return settled.isEmpty() ? rows : settled;
            }
            sleepQuietly(100L);
        }
        return List.of();
    }

    private List<AuditRow> queryRows(String description) {
        return jdbc.query(
                "SELECT result, error_msg, module FROM t_operation_log WHERE description = ?",
                (rs, rowNum) -> new AuditRow(
                        rs.getInt("result"),
                        rs.getString("error_msg"),
                        rs.getInt("module")),
                description);
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 审计表查询结果（只取本测试关心的三列） */
    private record AuditRow(int result, String errorMsg, int module) {
    }

    @TestConfiguration
    static class AuditProbeConfig {
        @Bean
        AuditProbeService auditProbeService() {
            return new AuditProbeService();
        }
    }

    /**
     * 审计探针：一个只带 {@code @Log} 的普通 bean，本身不含任何业务语义。
     *
     * <p>刻意声明为 public 类 —— CGLIB 代理需要能够子类化它。</p>
     *
     * <p>{@code module/targetType/action} 取固定枚举值仅为让注解合法、使审计记录可落库，
     * 不参与断言（断言只依赖 description 定位记录）。{@code targetIdEL = "0"} 同理用常量表达式，
     * 避免依赖编译期参数名保留（{@code -parameters}）——SpelUtil 解析失败会静默返回 null。</p>
     */
    public static class AuditProbeService {

        /** 共享计数：用于证明业务方法确实被执行过（而非事务提前短路） */
        static final AtomicInteger SUCCESS_CALLS = new AtomicInteger();
        static final AtomicInteger THROW_CALLS = new AtomicInteger();

        @Log(module = OperationModuleEnum.SYSTEM,
                targetType = OperationTargetTypeEnum.NONE,
                targetIdEL = "0",
                action = OperationActionEnum.LOGIN,
                descriptionEL = "'" + DESC_ROLLBACK + "'")
        public void succeedForRollbackCase() {
            SUCCESS_CALLS.incrementAndGet();
        }

        @Log(module = OperationModuleEnum.SYSTEM,
                targetType = OperationTargetTypeEnum.NONE,
                targetIdEL = "0",
                action = OperationActionEnum.LOGIN,
                descriptionEL = "'" + DESC_COMMIT + "'")
        public void succeedForCommitCase() {
            SUCCESS_CALLS.incrementAndGet();
        }

        @Log(module = OperationModuleEnum.SYSTEM,
                targetType = OperationTargetTypeEnum.NONE,
                targetIdEL = "0",
                action = OperationActionEnum.LOGIN,
                descriptionEL = "'" + DESC_THROW + "'")
        public void throwBusinessFailure() {
            THROW_CALLS.incrementAndGet();
            throw new BusinessException("审计探针模拟业务失败");
        }
    }
}
