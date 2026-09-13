-- 测试库 campusHelper_test 表结构调整（对应 DDL 审查发现的 4 组问题）
-- 用法：由 db-init 工具执行；已按幂等原则编写（重复执行不报错的部分需先手动确认）

-- ① t_student.user_id 加唯一索引（防同一学号被并发绑定到多个账号；user_id 允许 NULL，多 NULL 不冲突）
ALTER TABLE t_student ADD CONSTRAINT uk_student_user UNIQUE (user_id);

-- ② t_notification_retry.message_body 扩容：varchar(500) -> text（长内容通知 JSON 不再截断）
ALTER TABLE t_notification_retry MODIFY COLUMN message_body text NOT NULL COMMENT '通知内容';

-- ③ t_hot_keywords 唯一索引 (keyword) -> (keyword, record_date)（跨天归档不再撞唯一索引）
ALTER TABLE t_hot_keywords DROP INDEX uk_keyword;
ALTER TABLE t_hot_keywords ADD CONSTRAINT uk_keyword_date UNIQUE (keyword, record_date);

-- ④a t_notification.message_id 统一为 bigint（与重试表类型一致）
ALTER TABLE t_notification MODIFY COLUMN message_id bigint unsigned NULL COMMENT '全局消息ID（雪花），用于幂等，唯一索引';

-- ④b t_rank_task_monthly.rankNum -> rank_num（下划线命名规范）
ALTER TABLE t_rank_task_monthly CHANGE COLUMN rankNum rank_num int NOT NULL COMMENT '当月排名';

-- ④c 删除遗留表 t_chat_message（聊天已迁 MongoDB）
DROP TABLE IF EXISTS t_chat_message;

-- ===== 2026-08-12 Admin 模块：t_admin 拆角色字段 =====
ALTER TABLE t_admin ADD COLUMN role TINYINT(1) NOT NULL DEFAULT 1 COMMENT '角色：0-超级管理员，1-普通管理员' AFTER status;
UPDATE t_admin SET role = 0, status = 0 WHERE status = 2;

-- ===== 2026-08-12 t_user 状态拆分：账号状态(status) 与 学生认证(auth_status) 正交 =====
-- 迁移映射：status=2(已认证) -> auth_status=1, status=0；status=0/1 -> auth_status=0（status 保持原值）
-- 幂等处理：auth_status 列已存在则跳过（MySQL 8.0 无 ADD COLUMN IF NOT EXISTS，靠异常捕获跳过）
ALTER TABLE t_user ADD COLUMN auth_status TINYINT(1) NOT NULL DEFAULT 0 COMMENT '学生认证状态：0-未认证，1-已认证' AFTER status;
UPDATE t_user SET auth_status = 1, status = 0 WHERE status = 2;
ALTER TABLE t_user MODIFY COLUMN status TINYINT(1) NOT NULL DEFAULT 0 COMMENT '账号状态：0-正常，1-禁用';
CREATE INDEX idx_auth_status ON t_user (auth_status);

-- ===== 2026-09-10 ES 同步可靠性改造：新增事务性发件箱表 =====
-- 业务写库与「同步到 ES 的意图」同事务落库（原子性），异步派发 + XXL-JOB 兜底
-- 同一 (data_type, data_id) 仅保留一行，后到的意图覆盖先到的，由唯一索引保证合并幂等
-- file_urls 语义：待清理的 OSS 文件（objectName，逗号分隔），ES 同步达成后清理该列并置空
CREATE TABLE IF NOT EXISTS t_es_sync_outbox
(
    id          bigint auto_increment COMMENT '主键'
        PRIMARY KEY,
    data_id     bigint                                NOT NULL COMMENT '业务数据ID（商品ID/任务ID）',
    data_type   varchar(32)                           NOT NULL COMMENT '数据类型（goods商品、task任务）',
    op_type     tinyint     DEFAULT 0                 NOT NULL COMMENT '操作类型：0-写入/更新，1-删除',
    es_version  bigint                                NOT NULL COMMENT 'ES外部版本号（进程内单调递增，防乱序）',
    file_urls   text                                  NULL COMMENT '待清理的OSS文件（objectName），逗号分隔；ES同步达成后删除并清空',
    retry_count int         DEFAULT 0                 NOT NULL COMMENT '已重试次数',
    status      tinyint     DEFAULT 0                 NOT NULL COMMENT '状态：0-待同步 1-同步成功 2-重试失败终止',
    error_msg   text                                  NULL COMMENT '最后一次失败的错误信息',
    create_time datetime    DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '创建时间',
    update_time datetime    DEFAULT CURRENT_TIMESTAMP NOT NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    CONSTRAINT uk_data_type_data_id UNIQUE (data_type, data_id)
) COMMENT 'ES同步发件箱表（事务性Outbox）';

CREATE INDEX idx_outbox_status ON t_es_sync_outbox (status);

-- 路线 B：OSS 清理已并入事务性 Outbox，旧的重试表与两条链路收敛为一张待办表，删除旧表
DROP TABLE IF EXISTS t_es_sync_retry;

-- ===== 2026-09-12 注释/DDL 审查修正：补并发防重唯一索引 + 列注释订正 =====
-- ⑤ t_goods_evaluation (order_id, from_uid) 唯一索引：同订单同人防并发重复评价
--    （GoodsEvaluationServiceImpl 捕获 DuplicateKeyException 兜底依赖此索引；存量若有重复数据需先清理）
ALTER TABLE t_goods_evaluation ADD CONSTRAINT uk_order_from UNIQUE (order_id, from_uid);

-- ⑥ t_notification_retry.message_id 唯一索引：生产者 nack 回调与消费者失败写重试表并发时幂等去重
--    （NotificationRetryServiceImpl.saveIfFail 捕获 DuplicateKeyException 兜底依赖此索引；存量若有重复数据需先清理）
ALTER TABLE t_notification_retry ADD CONSTRAINT uk_message_id UNIQUE (message_id);

-- ⑦ 评分列注释订正：业务校验范围为 1-5（原 0-5 与校验矛盾）
ALTER TABLE t_goods_evaluation MODIFY COLUMN score tinyint NOT NULL COMMENT '评价分数（1-5）';

-- ⑧ 任务申请状态列注释订正：补 3-已完成、4-已取消（与 TaskApplyStatus 枚举对齐）
ALTER TABLE t_task_application MODIFY COLUMN status tinyint(1) DEFAULT 0 NOT NULL COMMENT '0-待处理 1-已接受 2-已拒绝 3-已完成 4-已取消';

-- ===== 2026-09-13 t_task 任务状态枚举「语义重排」的数据迁移（一次性，不可重复执行） =====
-- 背景：提交 068a238 修改了 TaskStatus 的枚举值定义，这属于「语义重排」而不是「尾部扩展」：
--     旧: 0-待接受   2-进行中   3-待确认完成   4-已完成   5-已取消
--     新: 0-待接单   1-进行中   2-待确认       3-已完成   4-已取消
-- 代码、EnumConstants、schema.sql 列注释都已同步，但【存量数据仍是旧编码】，必须迁移，否则：
--     status=2 → 进行中的任务被读成「待确认」
--     status=3 → 待确认的任务被读成「已完成」
--     status=4 → 已完成的任务被读成「已取消」
--     status=5 → 新枚举中不存在该值，MyBatis @EnumValue 反序列化直接抛异常（任务详情接口 500）
--
-- ⚠️ 执行前必做三项：
--   1) 备份：  CREATE TABLE t_task_bak_20260913 AS SELECT * FROM t_task;
--   2) 巡检值域（预期只有 0/2/3/4/5）：
--              SELECT status, COUNT(*) FROM t_task GROUP BY status ORDER BY status;
--   3) 确认改的是业务库 campusHelper 的 t_task，不是 t_activity.status（那是活动状态，含义完全不同）。
--      若巡检发现 status=5 记录数为 0 而 status=1 已有记录，说明本段可能已执行过，需人工确认后再决定是否继续。
--
-- ⚠️ 执行顺序必须是【从低到高】，不可调换：
--     所有新值都小于对应旧值，只有先把低位腾空，后面的赋值才不会踩到已迁好的数据。
--     反之若从高到低：5→4 会让 status=4 同时混入「旧4已完成」与「旧5已取消」，
--     紧接着的 4→3 会把这两批一并改成 3，数据彻底错乱且无法回滚。
--
-- ⚠️ 本段不是幂等语句（新旧值域重叠，无法用 WHERE 条件区分），重复执行必然错乱，只能执行一次。
--     建议与代码发布放在同一停机窗口内完成，迁移后立即执行下方校验。
START TRANSACTION;

UPDATE t_task SET status = 1 WHERE status = 2;  -- 旧 进行中     -> 新 进行中
UPDATE t_task SET status = 2 WHERE status = 3;  -- 旧 待确认完成 -> 新 待确认
UPDATE t_task SET status = 3 WHERE status = 4;  -- 旧 已完成     -> 新 已完成
UPDATE t_task SET status = 4 WHERE status = 5;  -- 旧 已取消     -> 新 已取消
-- status = 0（待接受/待接单）语义未变，无需迁移

COMMIT;

-- 执行后校验：结果应与第 0 步备份表逐值对比，且不存在 status >= 5 的行
--   SELECT status, COUNT(*) FROM t_task GROUP BY status ORDER BY status;
--
-- ⚠️ 配套动作：ES 的 task_index 中 status 存放的是代码写入的旧编码
--     （TaskEsSyncService 写入的是 TaskStatus.getCode()），MySQL 迁移后二者不再一致，
--     需要重建该索引。ES 是派生索引，以 MySQL 为准全量重灌即可，无需编写 ES 侧数据迁移脚本。
--
-- 📌 工程改进建议：本项目没有迁移版本记录表，脚本无法自证「是否已执行」。
--     引入一张 flyway_schema_history 风格的 t_schema_migration 表（记录脚本名 + 校验和 + 执行时间），
--     可让此类一次性脚本具备可追溯性，避免依赖人工记忆判断。

-- ===== 对照：t_task_application 为什么【不需要】迁移 =====
-- 上面 ⑧ 只把列注释补成 0-待处理 1-已接受 2-已拒绝 3-已完成 4-已取消。
-- 相比旧枚举，这是【在尾部追加】新值，0/1/2/3 的既有语义完全不变，属安全的「枚举扩展」。
-- 结论：枚举「扩展」无需迁移；枚举「重排」（让已有数字换含义）必须迁移，且要注意赋值顺序。

-- ===== 2026-09-13 审计链路修复：t_operation_log 补进脚本 + 三个枚举列注释订正 =====
-- 背景：LogAspect 缺 @Aspect，Spring AOP 未将其解析为切面，60+ 个 @Log 注解从未生效，
--     该表自建库起从未被写入（live 库 0 行、跑完 189 个用例的测试库也是 0 行）。
--     切面修复后本表成为硬依赖，而它此前【只存在于 live 开发库】，
--     schema.sql 与 alter.sql 均无 DDL —— 任何按脚本新建的库一上线就会写库失败。
--
-- ⑨ 幂等补建（live/测试库均已存在该表，此处仅保证「全新库 + 已有库」都能收敛到同一结构）
CREATE TABLE IF NOT EXISTS t_operation_log
(
    id             bigint unsigned auto_increment COMMENT '日志主键'
        PRIMARY KEY,
    create_time    datetime     DEFAULT CURRENT_TIMESTAMP NOT NULL COMMENT '操作时间戳',
    operator_id    bigint unsigned                         NOT NULL COMMENT '操作人ID（匿名访问记哨兵值 0）',
    operator_role  tinyint                                 NOT NULL COMMENT '操作人角色 0-用户 1-管理员 2-超级管理员 3-匿名用户',
    trace_id       varchar(64)                             NULL COMMENT '链路追踪ID(字符串,可含横杠)',
    module         tinyint                                 NOT NULL COMMENT '操作模块 1-认证 2-用户 3-任务 4-商品 5-系统 6-通知 7-聊天 8-搜索 9-管理员',
    target_type    tinyint                                 NULL COMMENT '操作对象类型 0-无 1-用户 2-任务 3-商品 4-订单 5-分类 6-评价 7-地址 8-申请 9-管理员',
    target_id      bigint unsigned                         NULL COMMENT '操作对象ID',
    action         int                                     NOT NULL COMMENT '操作类型编码(OperationActionEnum.code)',
    description    varchar(500)                            NULL COMMENT '操作描述',
    result         tinyint      DEFAULT 0                  NOT NULL COMMENT '操作结果 0-成功 1-失败',
    error_msg      varchar(500)                            NULL COMMENT '失败原因摘要',
    device_id      bigint                                  NULL COMMENT '设备ID(仅USER填写，无设备上下文时为空)',
    request_uri    varchar(255)                            NULL COMMENT '请求URI',
    request_method varchar(10)                             NULL COMMENT '请求方法',
    duration_ms    int                                     NULL COMMENT '接口耗时(ms)',
    KEY idx_trace_id (trace_id),
    KEY idx_operator (operator_id, operator_role),
    KEY idx_role_action (operator_role, module, action),
    KEY idx_target (target_type, target_id),
    KEY idx_create_time (create_time)
) COMMENT '统一操作审计日志表';

-- ⑩ 三个枚举列注释订正（live 库注释停留在旧枚举，与当前 Java 枚举不一致）
--    operator_role 最严重：库注释 1-用户 2-管理员 3-超级管理员，实际是 0/1/2/3 —— 整体偏移一位且缺匿名，
--                   照注释排查会把「匿名用户(3)」误读成「超级管理员(3)」。
--    module / target_type 属尾部扩展未同步：分别缺 9-管理员、7-地址 / 8-申请 / 9-管理员。
--    注：本表无历史数据（切面从未织入），因此只订正注释，无需数据迁移。
ALTER TABLE t_operation_log MODIFY COLUMN operator_role tinyint NOT NULL COMMENT '操作人角色 0-用户 1-管理员 2-超级管理员 3-匿名用户';
ALTER TABLE t_operation_log MODIFY COLUMN module tinyint NOT NULL COMMENT '操作模块 1-认证 2-用户 3-任务 4-商品 5-系统 6-通知 7-聊天 8-搜索 9-管理员';
ALTER TABLE t_operation_log MODIFY COLUMN target_type tinyint NULL COMMENT '操作对象类型 0-无 1-用户 2-任务 3-商品 4-订单 5-分类 6-评价 7-地址 8-申请 9-管理员';
