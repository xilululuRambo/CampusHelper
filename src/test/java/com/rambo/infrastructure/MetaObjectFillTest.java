package com.rambo.infrastructure;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.rambo.BaseApiTest;
import com.rambo.module.task.pojo.entity.Task;
import com.rambo.module.task.server.service.TaskService;
import jakarta.annotation.Resource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 自动填充测试：{@code MyMetaObjectHandler} 的 createTime / updateTime。
 *
 * <p><b>为什么这类"框架自动行为"值得单测</b>：{@code createTime} / {@code updateTime} 被 15+ 实体
 * 声明为 {@code @TableField(fill = ...)}，业务代码从不显式赋值，全靠这个 handler 兜底。
 * 一旦 handler 失效（未注册、strict 语义不匹配、字段名写错），失败方式是**静默的**——
 * 插入不会报错，只是所有行的 createTime 变成 NULL，直到前端按创建时间排序、或者运维按时间排查
 * 才发现数据已是脏的，而那时存量数据已经无法补救。</p>
 *
 * <p>这组用例走真实 MyBatis-Plus 插入链路（真实库 + 真实 handler），而非直接调 handler 方法：
 * 直接调 {@code insertFill} 只能证明方法体内的赋值语句成立，无法覆盖
 * 「handler 是否被 MyBatis-Plus 认到」「实体字段名与 handler 里的属性名是否一致」——
 * 而后者恰是这类配置最常见的故障点（handler 写 {@code createTime}，实体却叫 {@code createdAt}，
 * 编译期不报错、运行期静默不填）。</p>
 */
class MetaObjectFillTest extends BaseApiTest {

    @Resource
    private TaskService taskService;

    @Resource
    private JdbcTemplate jdbcTemplate;

    @Resource
    private MetaObjectHandler metaObjectHandler;

    @Test
    @DisplayName("handler 被 Spring 容器注册（否则所有实体的填充字段都是 NULL）")
    void handler_isRegistered() {
        assertThat(metaObjectHandler)
                .as("MyMetaObjectHandler 必须作为 bean 存在，MyBatis-Plus 才会调用它")
                .isNotNull();
    }

    @Test
    @DisplayName("插入：createTime 与 updateTime 都被自动填上，且贴近当前时间")
    void insert_fillsBothTimestamps() {
        LocalDateTime before = LocalDateTime.now().minusSeconds(5);

        Task task = newTask();
        assertThat(taskService.save(task)).as("插入应当成功（此时 createTime 仍为 null）").isTrue();

        // 从库里回读，而不是看内存对象——要证明的是"落库的值"，不是"内存里被改了"
        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT create_time, update_time FROM t_task WHERE id = ?", task.getId());

        Object createTime = row.get("create_time");
        Object updateTime = row.get("update_time");

        assertThat(createTime)
                .as("createTime 未落库说明填充失效——业务代码从不显式赋值，NULL 会一直静默存在")
                .isNotNull();
        assertThat(updateTime).as("updateTime 在 insert 阶段同样应由 INSERT_UPDATE 填充").isNotNull();

        LocalDateTime created = toLocalDateTime(createTime);
        assertThat(created)
                .as("填充的应是当前时间而非某个固定/默认值")
                .isAfter(before)
                .isBefore(LocalDateTime.now().plusSeconds(5));
    }

    @Test
    @DisplayName("更新：updateTime 被刷新，createTime 不被改动")
    void update_refreshesUpdateTime_only() {
        Task task = newTask();
        taskService.save(task);

        Map<String, Object> beforeRow = jdbcTemplate.queryForMap(
                "SELECT create_time, update_time FROM t_task WHERE id = ?", task.getId());
        LocalDateTime createTimeBefore = toLocalDateTime(beforeRow.get("create_time"));

        // 让 updateTime 与 createTime 明确拉开距离，避免「同一毫秒」让断言失去区分度
        sleepQuietly(1100);

        Task patch = new Task();
        patch.setId(task.getId());
        patch.setTitle("被更新过的标题");
        assertThat(taskService.updateById(patch)).isTrue();

        Map<String, Object> afterRow = jdbcTemplate.queryForMap(
                "SELECT create_time, update_time FROM t_task WHERE id = ?", task.getId());

        LocalDateTime createTimeAfter = toLocalDateTime(afterRow.get("create_time"));
        LocalDateTime updateTimeAfter = toLocalDateTime(afterRow.get("update_time"));

        assertThat(createTimeAfter)
                .as("createTime 是 INSERT 语义，更新时必须原样保留——"
                        + "若被刷新，任务的实际创建时间将永久丢失")
                .isEqualTo(createTimeBefore);
        assertThat(updateTimeAfter)
                .as("updateTime 是 INSERT_UPDATE 语义，每次更新都应刷新")
                .isAfter(createTimeAfter);
        assertThat(ChronoUnit.MILLIS.between(createTimeAfter, updateTimeAfter))
                .as("updateTime 与 createTime 应拉开约 1 秒（否则可能是同一时刻的巧合值）")
                .isGreaterThanOrEqualTo(1000L);
    }

    @Test
    @DisplayName("strict 语义：实体已有值时不覆盖（调用方显式赋值优先）")
    void strictFill_doesNotOverwriteProvidedValue() {
        LocalDateTime pinned = LocalDateTime.of(2020, 1, 2, 3, 4, 5);

        Task task = newTask();
        task.setCreateTime(pinned);   // 模拟数据迁移/回填场景：调用方显式指定
        task.setUpdateTime(pinned);
        taskService.save(task);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT create_time FROM t_task WHERE id = ?", task.getId());

        assertThat(toLocalDateTime(row.get("create_time")))
                .as("strictInsertFill 的语义是「仅当值为空才填」。若这里被改成当前时间，"
                        + "说明用了非 strict 的 setFieldValByName——那会覆盖导入/迁移数据的原始时间戳")
                .isEqualTo(pinned);
    }

    // ==================== 辅助 ====================

    /**
     * 构造一个可落库的 Task。
     *
     * <p>两个库约束必须满足，否则插入直接失败、根本走不到填充逻辑：</p>
     * <ul>
     *   <li>{@code publisher_id} NOT NULL 且无默认值 —— 必须显式赋值；</li>
     *   <li>{@code t_task_ibfk_1} 外键指向 {@code t_user(id)} —— 不能用魔法数字，
     *       必须取一个真实存在的用户 ID。因此本类先注册一个真实用户再建任务。</li>
     * </ul>
     */
    private Task newTask() {
        Task task = new Task();
        task.setTitle("填充测试-" + System.nanoTime());
        task.setDescription("验证 createTime/updateTime 自动填充");
        task.setReward(5);
        task.setCategoryId(1L);
        task.setAddressId(1L);
        task.setPublisherId(publisherId());
        task.setDeadline(LocalDateTime.now().plusDays(7));
        return task;
    }

    /** 外键要求 publisher_id 必须指向真实存在的 t_user 行；每个用例各自注册一个，互不干扰 */
    private Long publisherId() {
        return newAuthedUser().getUserId();
    }

    /**
     * JDBC 读回来的时间列在不同驱动/时区配置下可能是 {@code Timestamp} 或 {@code LocalDateTime}，
     * 统一转成 LocalDateTime 再比较，避免断言依赖驱动的具体返回类型。
     */
    private LocalDateTime toLocalDateTime(Object value) {
        if (value instanceof LocalDateTime ldt) {
            return ldt;
        }
        if (value instanceof java.sql.Timestamp ts) {
            return ts.toLocalDateTime();
        }
        throw new AssertionError("无法识别的时间列类型: " + (value == null ? "null" : value.getClass()));
    }

    private static void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
