-- CampusHelper 测试库表结构（由开发库 mysqldump --no-data 导出，2026-08-05）
-- 执行前提：CREATE DATABASE campusHelper_test DEFAULT CHARSET utf8mb4;

create table t_activity
(
    id          bigint unsigned auto_increment comment '活动ID'
        primary key,
    title       varchar(100)             not null comment '活动标题',
    cover       varchar(255)             null comment '活动封面图片URL',
    location    varchar(255)             not null comment '活动地点',
    longitude   decimal(10, 7)           null comment '经度',
    latitude    decimal(10, 7)           null comment '纬度',
    start_time  datetime                 not null comment '活动开始时间',
    end_time    datetime                 null comment '活动结束时间',
    capacity    int unsigned default '0' null comment '最大报名人数，0表示不限制',
    status      tinyint(1)   default 1   null comment '活动状态：0-已取消，1-报名中，2-进行中，3-已结束',
    create_time datetime                 not null comment '创建时间',
    update_time datetime                 not null comment '更新时间'
)
    comment '活动表';

create index idx_start_time
    on t_activity (start_time);

create index idx_status
    on t_activity (status);

create table t_activity_signup
(
    id          bigint unsigned auto_increment comment '报名记录ID'
        primary key,
    activity_id bigint unsigned      not null comment '活动ID',
    user_id     bigint unsigned      not null comment '报名用户ID',
    create_time datetime             not null comment '报名时间',
    status      tinyint(1) default 1 null comment '报名状态：0-已取消，1-已报名，2-已签到',
    remark      varchar(255)         null comment '备注',
    update_time datetime             not null comment '更新时间',
    constraint uk_activity_user
        unique (activity_id, user_id)
)
    comment '活动报名表';

create index idx_activity_id
    on t_activity_signup (activity_id);

create index idx_user_id
    on t_activity_signup (user_id);

create table t_es_sync_outbox
(
    id           bigint auto_increment comment '主键（同时充当 ES 外部版本号：自增 id 严格单调）'
        primary key,
    data_id      bigint                                not null comment '业务数据ID（商品ID/任务ID）',
    data_type    varchar(32)                           not null comment '数据类型（goods商品、task任务）',
    op_type      tinyint     default 0                 not null comment '操作类型：0-写入/更新，1-删除',
    file_urls    text                                  null comment '待清理的OSS文件（objectName），逗号分隔；ES同步达成后删除并清空',
    retry_count  int         default 0                 not null comment '已重试次数（达到 MAX 标记 FAILED 终止）',
    status       tinyint     default 0                 not null comment '状态：0-待同步 1-同步成功 2-重试失败终止',
    error_msg    text                                  null comment '最后一次失败的错误信息',
    create_time  datetime    default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  datetime    default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    next_retry_at datetime(3) default CURRENT_TIMESTAMP(3) not null comment '下次可重试时间（指数退避：失败后 = NOW + min(30*2^retryCount, 600) 秒）'
)
    comment 'ES同步发件箱表（事务性Outbox）';

-- 同一 (data_type, data_id) 允许多行：每条 enqueue 都是独立行，row.id 即 ES 外部版本号。
-- 派发按 id ASC 顺序处理，ES external version 天然按时间序单调递增，旧写入会被 409 拒绝。
-- next_retry_at 用 DATETIME(3)：DATETIME(0) 会把小数秒四舍五入（.797 → 下一秒），
-- 导致刚登记的行在「同一秒内的 NOW」比较下查不出来。

create index idx_outbox_status_retry
    on t_es_sync_outbox (status, next_retry_at);

create table t_goods
(
    id          bigint                             not null comment '商品ID（雪花算法生成）'
        primary key,
    owner_id    bigint                             not null comment '发布者用户ID',
    title       varchar(200)                       not null comment '商品标题',
    description text                               null comment '商品描述',
    price       bigint                             not null comment '商品价格 单位：分',
    images      varchar(2000)                      not null comment '商品图片 OSS 对象名，多张用逗号分隔',
    status      tinyint  default 0                 not null comment '商品状态：0-在售 1-交易中 2-下架  3-已售出',
    admin_disabled tinyint default 0               not null comment '管理员强制下架标记：0-正常 1-管理员下架（商家不可自行恢复）',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    category_id bigint                             not null comment '商品分类ID',
    deleted     int      default 0                 null comment '逻辑删除 0未删 1已删',
    version     int      default 0                 not null comment '乐观锁版本号'
)
    comment '商品表';

create index idx_category_id
    on t_goods (category_id);

create index idx_create_time
    on t_goods (create_time);

create index idx_owner_id
    on t_goods (owner_id);

create index idx_price
    on t_goods (price);

create index idx_status
    on t_goods (status);

create table t_goods_category
(
    id          bigint auto_increment comment '商品分类ID'
        primary key,
    name        varchar(100)                       not null comment '商品分类名称',
    description varchar(500)                       null comment '商品分类描述',
    status      tinyint  default 0                 not null comment '商品分类状态：0-正常，1-禁用',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique key uk_name (name)
)
    comment '商品分类表';

create table t_goods_evaluation
(
    id          bigint                             not null comment '商品评价ID（雪花算法生成）'
        primary key,
    order_id    bigint                             not null comment '商品订单ID',
    from_uid    bigint                             not null comment '评价人用户ID',
    to_uid      bigint                             not null comment '被评价人用户ID',
    score       tinyint                            not null comment '评价分数（1-5）',
    content     varchar(1000)                      null comment '评价内容',
    create_time datetime default CURRENT_TIMESTAMP not null comment '评价时间',
    constraint uk_order_from
        unique (order_id, from_uid)
)
    comment '商品评价表';

create index idx_from_uid
    on t_goods_evaluation (from_uid);

create index idx_order_id
    on t_goods_evaluation (order_id);

create index idx_to_uid
    on t_goods_evaluation (to_uid);

create table t_goods_order
(
    id            bigint                             not null comment '订单ID（雪花算法生成）'
        primary key,
    owner_id      bigint                             not null comment '发布者用户ID（卖家）',
    buyer_id      bigint                             not null comment '购买者用户ID（买家）',
    total_amount  bigint                             not null comment '订单总金额 单位：分',
    order_status  tinyint  default 0                 not null comment '订单状态：0-待付款，1-待发货，2-待收货，3-已完成 ，4-已取消',
    pay_time      datetime                           null comment '付款时间',
    ship_time     datetime                           null comment '发货时间',
    confirm_time  datetime                           null comment '确认收货时间',
    create_time   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time   datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    goods_id      bigint                             not null comment '商品id',
    buyer_deleted int      default 0                 not null comment '买家逻辑删除 0未删 1已删',
    version       int      default 0                 not null comment '乐观锁版本号',
    owner_deleted int      default 0                 not null comment '卖家逻辑字段'
)
    comment '商品订单表';

create index idx_buyer_id
    on t_goods_order (buyer_id);

create index idx_buyer_status_time
    on t_goods_order (buyer_id, order_status, create_time);

create index idx_create_time
    on t_goods_order (create_time);

create index idx_order_status
    on t_goods_order (order_status);

create index idx_owner_id
    on t_goods_order (owner_id);

create index idx_owner_status_time
    on t_goods_order (owner_id, order_status, create_time);

create table t_goods_order_item
(
    id          bigint        not null comment '订单项主键ID（雪花算法生成）'
        primary key,
    order_id    bigint        not null comment '订单ID',
    goods_id    bigint        not null comment '商品ID',
    goods_title varchar(200)  not null comment '商品标题（快照）',
    price       bigint        not null comment '商品单价（快照）',
    description text          null comment '商品描述',
    images      varchar(2000) not null comment '商品图片'
)
    comment '订单商品项表';

create index idx_goods_id
    on t_goods_order_item (goods_id);

create index idx_order_id
    on t_goods_order_item (order_id);

create table t_hot_keywords
(
    id           bigint auto_increment
        primary key,
    keyword      varchar(50)                        not null,
    search_count int                                not null,
    update_time  datetime default CURRENT_TIMESTAMP null on update CURRENT_TIMESTAMP,
    record_date  date                               null comment '记录日期',
    constraint uk_keyword_date
        unique (keyword, record_date)
)
    comment '热门搜索';

create table t_notification
(
    id          bigint unsigned auto_increment comment '通知ID'
        primary key,
    user_id     bigint unsigned      not null comment '接收通知的用户ID',
    type        tinyint(1)           not null comment '通知类型：1-任务被接单，2-申请被拒绝，3-任务完成，4-收到新评价 5-管理员审核结果',
    content     varchar(500)         not null comment '通知内容',
    is_read     tinyint(1) default 0 null comment '是否已读：0-未读，1-已读',
    create_time datetime             not null comment '通知发送时间',
    update_time datetime             not null comment '更新时间',
    message_id  bigint unsigned      null comment '全局消息ID（雪花），用于幂等，唯一索引',
    ref_id      bigint               null comment '关联业务ID（任务ID/订单ID等），前端跳转用',
    constraint idx_message_id
        unique (message_id)
)
    comment '通知表';

create index idx_create_time
    on t_notification (create_time);

create index idx_is_read
    on t_notification (is_read);

create index idx_user_id
    on t_notification (user_id);

create table t_notification_retry
(
    id            bigint unsigned auto_increment comment '通知重试ID'
        primary key,
    message_id    bigint unsigned      not null comment '通知ID',
    message_body  text                 not null comment '通知内容',
    status        tinyint(1) default 0 not null comment '状态 0-待重试 1-重试成功 2-重试失败终止',
    retry_count   int        default 0 not null comment '重试次数',
    error_message varchar(500)         not null comment '错误信息',
    create_time   datetime             not null comment '创建时间',
    update_time   datetime             not null comment '更新时间',
    constraint uk_message_id
        unique (message_id)
)
    comment '通知重试表';

create table t_rank_task_monthly
(
    id           bigint auto_increment
        primary key,
    user_id      bigint                             not null,
    finish_count int      default 0                 not null,
    rank_num     int                                not null comment '当月排名',
    month        varchar(7)                         not null comment '2026-06',
    created_at   datetime default CURRENT_TIMESTAMP null
)
    comment '月度任务达人榜归档';

create index idx_month
    on t_rank_task_monthly (month);

create table t_task_category
(
    id          bigint unsigned auto_increment
        primary key,
    name        varchar(30)                          not null comment '分类名称',
    description varchar(100)                         not null comment '分类描述',
    status      tinyint(1) default 0                 not null comment '0-启用 1-禁用',
    create_time datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    unique key uk_name (name)
)
    comment '任务分类表';

create table t_user
(
    id           bigint unsigned auto_increment
        primary key,
    phone        varchar(11)                          null comment '手机号',
    real_name    varchar(20)                          null comment '真实姓名',
    student_id   varchar(32)                          null comment '学号',
    avatar       varchar(255)                         null comment '头像 OSS 对象名',
    username     varchar(15)                          not null comment '用户名',
    points       int        default 0                 not null comment '积分',
    credit_score int        default 80                not null comment '信誉分',
    status       tinyint(1) default 0                 not null comment '账号状态：0-正常，1-禁用',
    auth_status  tinyint(1) default 0                 not null comment '学生认证状态：0-未认证，1-已认证',
    create_time  datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    balance      bigint     default 0                 not null comment '用户余额 单位：分',
    version      int        default 0                 not null comment '乐观锁版本号',
    constraint idx_username
        unique (username),
    constraint phone
        unique (phone),
    constraint student_id
        unique (student_id)
);

create table t_address
(
    id             bigint auto_increment comment '地址ID'
        primary key,
    user_id        bigint unsigned                      not null comment '用户ID',
    receiver_name  varchar(32)                          not null comment '收货人姓名',
    receiver_phone varchar(20)                          not null comment '收货人手机号',
    province       varchar(32)                          not null comment '省',
    city           varchar(32)                          not null comment '市',
    district       varchar(32)                          not null comment '区/县',
    detail_address varchar(128)                         not null comment '详细地址',
    is_default     tinyint(1) default 0                 not null comment '是否默认地址：0-否，1-是',
    create_time    datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time    datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint t_address_ibfk_1
        foreign key (user_id) references t_user (id)
)
    comment '用户地址管理表';

create index idx_is_default
    on t_address (is_default);

create index idx_user_id
    on t_address (user_id);

create table t_student
(
    id          bigint                             not null comment '学生ID'
        primary key,
    student_id  varchar(32)                        not null comment '学号',
    real_name   varchar(20)                        not null comment '真实姓名',
    major       varchar(50)                        not null comment '专业',
    grade       varchar(20)                        not null comment '年级',
    college     varchar(50)                        not null comment '学院',
    user_id     bigint unsigned                    null comment '绑定用户ID',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_student_id
        unique (student_id),
    constraint uk_student_user
        unique (user_id),
    constraint t_student_t_user_id_fk
        foreign key (user_id) references t_user (id)
)
    comment '学生信息表';

create index idx_user_id
    on t_student (user_id);

create table t_task
(
    id           bigint unsigned auto_increment
        primary key,
    publisher_id bigint unsigned                      not null comment '发布者ID',
    title        varchar(100)                         not null comment '任务标题',
    description  varchar(500)                         not null comment '任务描述',
    reward       int                                  not null comment '任务赏金',
    category_id  bigint unsigned                      not null comment '分类ID',
    address_id   bigint                               not null comment '地址ID',
    deadline     datetime                             not null comment '截止时间',
    status       tinyint(1) default 0                 not null comment '0-待接单 1-进行中 2-待确认 3-已完成 4-已取消',
    create_time  datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time  datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    applicant_id bigint unsigned                      null,
    version      int        default 0                 not null comment '乐观锁版本号',
    constraint t_task_applicant_id
        foreign key (applicant_id) references t_user (id),
    constraint t_task_ibfk_1
        foreign key (publisher_id) references t_user (id),
    constraint t_task_ibfk_2
        foreign key (category_id) references t_task_category (id),
    constraint t_task_ibfk_3
        foreign key (address_id) references t_address (id)
)
    comment '任务表';

create index idx_address_id
    on t_task (address_id);

create index idx_applicant_id
    on t_task (applicant_id);

create index idx_category_id
    on t_task (category_id);

create index idx_create_time
    on t_task (create_time);

create index idx_deadline
    on t_task (deadline);

create index idx_publisher_id
    on t_task (publisher_id);

create index idx_status
    on t_task (status);

create index idx_status_time
    on t_task (status, create_time);

create table t_task_application
(
    id                bigint unsigned auto_increment
        primary key,
    task_id           bigint unsigned                      not null comment '任务ID',
    applicant_id      bigint unsigned                      not null comment '申请人ID',
    reason            varchar(255)                         not null comment '申请理由',
    status            tinyint(1) default 0                 not null comment '0-待处理 1-已接受 2-已拒绝 3-已完成 4-已取消',
    complete_evidence varchar(255)                         null comment '完成凭证',
    complete_time     datetime                             null comment '完成时间',
    create_time       datetime   default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time       datetime   default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    version           int        default 0                 not null comment '乐观锁版本号',
    constraint uk_task_applicant
        unique (task_id, applicant_id),
    constraint t_task_application_ibfk_1
        foreign key (task_id) references t_task (id),
    constraint t_task_application_ibfk_2
        foreign key (applicant_id) references t_user (id)
)
    comment '任务申请表';

create index idx_applicant_id
    on t_task_application (applicant_id);

create index idx_applicant_status
    on t_task_application (applicant_id, status);

create index idx_create_time
    on t_task_application (create_time);

create index idx_status
    on t_task_application (status);

create index idx_task_id
    on t_task_application (task_id);

create index idx_task_status
    on t_task_application (task_id, status);

create table t_task_order
(
    id           bigint unsigned auto_increment
        primary key,
    task_id      bigint unsigned                    not null comment '任务ID',
    publisher_id bigint unsigned                    not null comment '发布者ID',
    receiver_id  bigint unsigned                    not null comment '接单者ID',
    create_time  datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    constraint uk_task_id
        unique (task_id),
    constraint t_task_order_ibfk_1
        foreign key (task_id) references t_task (id),
    constraint t_task_order_ibfk_2
        foreign key (publisher_id) references t_user (id),
    constraint t_task_order_ibfk_3
        foreign key (receiver_id) references t_user (id)
)
    comment '任务订单表';

create table t_task_evaluation
(
    id          bigint unsigned auto_increment
        primary key,
    order_id    bigint unsigned                    not null comment '订单ID',
    from_uid    bigint unsigned                    not null comment '评价人ID',
    to_uid      bigint unsigned                    not null comment '被评价人ID',
    score       tinyint unsigned                   not null comment '评分 1-5',
    content     varchar(255)                       not null comment '评价内容',
    create_time datetime default CURRENT_TIMESTAMP not null comment '评价时间',
    constraint uk_order_from
        unique (order_id, from_uid),
    constraint t_task_evaluation_ibfk_1
        foreign key (order_id) references t_task_order (id),
    constraint t_task_evaluation_ibfk_2
        foreign key (from_uid) references t_user (id),
    constraint t_task_evaluation_ibfk_3
        foreign key (to_uid) references t_user (id)
)
    comment '评价表';

create index idx_from_uid
    on t_task_evaluation (from_uid);

create index idx_order_id
    on t_task_evaluation (order_id);

create index idx_to_uid
    on t_task_evaluation (to_uid);

create index idx_publisher_id
    on t_task_order (publisher_id);

create index idx_receiver_id
    on t_task_order (receiver_id);

create index idx_task_id
    on t_task_order (task_id);

create index idx_status
    on t_user (status);

create index idx_auth_status
    on t_user (auth_status);

create table t_admin
(
    id          bigint                            not null comment '管理员ID（雪花算法生成）'
        primary key,
    account     varchar(11)                       not null comment '管理员账号（11位数字）',
    password    varchar(100)                      not null comment 'BCrypt密码哈希',
    name        varchar(20)                       null comment '管理员姓名',
    phone       varchar(11)                       null comment '管理员手机号',
    status      tinyint(1) default 0              not null comment '管理员状态：0-正常，1-禁用',
    role        tinyint(1) default 1              not null comment '管理员角色：0-超级管理员，1-普通管理员',
    create_time datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    update_time datetime default CURRENT_TIMESTAMP not null on update CURRENT_TIMESTAMP comment '更新时间',
    constraint uk_account
        unique (account)
)
    comment '管理员表';

-- 补录（2026-08-24）：开发库与测试库原本均缺失 t_task_rank_monthly（TaskRankArchiveJob 归档依赖），
-- 已按实体字段在测试库手工补建。开发库需同步执行。
create table t_task_rank_monthly
(
    id           bigint unsigned auto_increment
        primary key,
    user_id      bigint unsigned             not null comment '用户ID',
    finish_count int                         not null comment '完成任务数量',
    rank_num     int                         not null comment '当月排名',
    month        varchar(7)                  not null comment '月份 yyyy-MM',
    created_at   datetime default CURRENT_TIMESTAMP not null comment '创建时间',
    constraint uk_user_month
        unique (user_id, month)
)
    comment '月任务排名归档表';

create index idx_month
    on t_task_rank_monthly (month);

-- 补录（2026-09-13）：t_operation_log 此前【只存在于 live 开发库】，schema.sql 与 alter.sql 均无 DDL，
-- 属「手工建表后未回收进脚本」的遗漏。LogAspect 此前因缺 @Aspect 从未真正织入，
-- 该表长期无人写入，问题被掩盖；切面修复后本表成为必需依赖，故补齐全量 DDL。
-- 列注释已按 OperationModuleEnum / OperationTargetTypeEnum / OperatorRoleEnum 的【当前】code 订正
-- （live 库注释停留在旧枚举：operator_role 整体偏移一位且缺匿名，module/target_type 缺尾部新增值）。
create table t_operation_log
(
    id             bigint unsigned auto_increment comment '日志主键'
        primary key,
    create_time    datetime     default CURRENT_TIMESTAMP not null comment '操作时间戳',
    operator_id    bigint unsigned                         not null comment '操作人ID（匿名访问记哨兵值 0）',
    operator_role  tinyint                                 not null comment '操作人角色 0-用户 1-管理员 2-超级管理员 3-匿名用户',
    trace_id       varchar(64)                             null comment '链路追踪ID(字符串,可含横杠)',
    module         tinyint                                 not null comment '操作模块 1-认证 2-用户 3-任务 4-商品 5-系统 6-通知 7-聊天 8-搜索 9-管理员',
    target_type    tinyint                                 null comment '操作对象类型 0-无 1-用户 2-任务 3-商品 4-订单 5-分类 6-评价 7-地址 8-申请 9-管理员',
    target_id      bigint unsigned                         null comment '操作对象ID',
    action         int                                     not null comment '操作类型编码(OperationActionEnum.code)',
    description    varchar(500)                            null comment '操作描述',
    result         tinyint      default 0                  not null comment '操作结果 0-成功 1-失败',
    error_msg      varchar(500)                            null comment '失败原因摘要',
    device_id      bigint                                  null comment '设备ID(仅USER填写，无设备上下文时为空)',
    request_uri    varchar(255)                            null comment '请求URI',
    request_method varchar(10)                             null comment '请求方法',
    duration_ms    int                                     null comment '接口耗时(ms)'
)
    comment '统一操作审计日志表';

create index idx_trace_id
    on t_operation_log (trace_id);

create index idx_operator
    on t_operation_log (operator_id, operator_role);

create index idx_role_action
    on t_operation_log (operator_role, module, action);

create index idx_target
    on t_operation_log (target_type, target_id);

create index idx_create_time
    on t_operation_log (create_time);
