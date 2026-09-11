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
