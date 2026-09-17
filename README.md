# CampusHelper 校园互助系统
# 已于9月份从gitee平台迁移到Github平台。
# 原gitee仓库地址：https://gitee.com/xilululu/campus-helper.git

<div align="center">

**一个面向大学生的校园任务互助 + 二手交易平台**

[![Java](https://img.shields.io/badge/Java-17-orange)](https://www.oracle.com/java/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2.5-brightgreen)](https://spring.io/projects/spring-boot)
[![MyBatis-Plus](https://img.shields.io/badge/MyBatis--Plus-3.5.5-blue)](https://baomidou.com/)
[![License](https://img.shields.io/badge/License-MIT-lightgrey)](./LICENSE)

</div>

## 📖 项目简介

CampusHelper 是一个面向高校学生的校园互助平台，将「任务互助」与「二手交易」两大高频校园场景整合为一个系统。学生可以发布任务（代取快递、跑腿、辅导等）赚取报酬，也可以买卖二手商品；系统提供站内通知、实时聊天、管理后台等完整配套能力。

项目为 **单体架构 + 分层设计**，独立完成需求分析、数据库设计与后端全部编码，共 **7 个业务模块**。重点打磨了认证安全、消息可靠性、并发一致性、缓存一致性等后端核心难点，可作为 Java 后端求职与学习的完整实战案例。

## 🛠 技术栈

| 分类 | 技术 | 版本 |
|---|---|---|
| 核心框架 | Spring Boot | 3.2.5 |
| 语言 / 运行时 | Java | 17 |
| ORM | MyBatis-Plus | 3.5.5 |
| 关系型数据库 | MySQL | 8.x |
| 缓存 / 分布式锁 | Redis（Spring Data Redis + Redisson） | Redisson 3.23.5 |
| 消息队列 | RabbitMQ（Spring AMQP） | — |
| 全文检索 | Elasticsearch | 8.11.0 |
| 文档数据库 | MongoDB（聊天消息存储） | — |
| 任务调度 | XXL-JOB | 2.4.1 |
| 实时通信 | WebSocket（SockJS + STOMP） | — |
| 认证 | JWT（jjwt） | 0.12.5 |
| 对象存储 | 阿里云 OSS | SDK 3.18.1 |
| 接口文档 | Knife4j（OpenAPI 3） | 4.4.0 |
| 工具库 | Hutool / Lombok | 5.8.25 / 1.18.32 |
| 密码加密 | Spring Security Crypto（BCrypt） | — |

## 🏗 架构设计

项目采用 **单体应用 + 三层包结构** 组织，模块边界清晰、依赖单向：

```
com.rambo
├── common          # 通用层：常量、上下文、枚举、异常、统一返回、工具类
├── infrastructure  # 基础设施层：AOP、认证、缓存、配置、消息、重试、搜索、存储、WebSocket
└── module          # 业务层：7 个业务模块，每个模块内部按 controller/mapper/service/entity/dto/vo 分层
```

**依赖方向**：`module → infrastructure → common`，业务层不反向依赖基础设施实现细节，通过接口 + 依赖倒置解耦。

**7 个业务模块**：

| 模块 | 职责 |
|---|---|
| `user` | 用户注册登录、学生认证、积分/信誉分、收货地址 |
| `task` | 任务发布、接单、状态流转、评价、达人榜/热搜 |
| `goods` | 二手商品发布、下单、支付、订单状态机 |
| `notification` | 站内通知、消息消费、重试补偿 |
| `chat` | 实时聊天（WebSocket）、会话管理、历史消息 |
| `admin` | 管理后台：用户/任务/商品/分类管理、管理员认证 |
| `operationlog` | 操作日志记录（AOP 切面） |

## ✨ 核心亮点

> 以下是项目重点打磨的后端难点，每一个都有对应的实现与踩坑演进过程。

### 1. 认证与安全：JWT 双 Token + Refresh Token 轮换

- **痛点**：无状态 JWT 无法主动吊销，无法做会话管理。
- **方案**：Access Token（30min）无状态校验身份 + Refresh Token（7 天）有状态管理会话。
- **实现**：Refresh Token 存入 Redis（RMapCache，field 级 TTL），支持多设备隔离、主动下线、黑名单、滑动续期；旧 RT 重放时与 Redis 比对不一致，判定设备被盗并仅踢出该设备，避免单点泄露导致全设备下线。

### 2. 消息可靠性：四层保障「不丢失」+ 幂等「不重复」

- **不丢失**：发布确认（correlated）→ 手动 ACK → 本地重试表 → 死信队列，四层兜底。
- **不重复**：消费端以 `messageId` + 唯一索引实现幂等。
- **一致性**：所有消息发送延迟到数据库事务提交后（`afterCommit`）执行，事务回滚不产生假通知。

### 3. 并发与一致性：分布式锁 + 乐观锁 + 条件更新

- 任务 / 订单状态机采用 **Redisson 分布式锁**（锁释放下沉到事务提交后，防并发接单 / 重复退款）+ **乐观锁**双重保护。
- 余额扣减使用**条件更新**（`balance >= amount`）保证原子性与不超扣。
- Redis 原子删除实现**防重复提交**。

### 4. 搜索与补偿：ES 外部版本号 + XXL-JOB 定时补偿

- 用**外部版本号**（`updateTime`）防止并发乱序覆盖。
- 同步失败写入本地重试表，由 XXL-JOB 定时补偿，超过次数进入人工处理，保证 MySQL 与 ES 最终一致。

### 5. 缓存一致性：旁路缓存失效

- Cache-Aside 模式，先更新数据库再删除缓存 + 过期时间兜底。
- 针对「CAS 直接改库绕过 `@CacheEvict` 导致缓存残留」的踩坑，专门增加显式失效方法，覆盖全写路径。

### 6. 排行榜 / 热搜：Redis ZSet + 原子归档

- 用 ZSet 实现排行榜 / 热搜。
- 每日归档使用 **RENAME 原子轮换**，规避「先写库再删缓存」的查改非原子竞态导致的数据丢失，配合孤儿 key 收编 + 幂等防重实现失败自愈。

### 7. 实时聊天：WebSocket 越权防护

- SockJS + STOMP + MongoDB 会话存储。
- 越权防护三道防线：废弃前端传入的 `receiverId`、服务端从业务绑定推导参与者、读写均做参与方校验，杜绝「前端随意传值」导致的越权消息。

## 🗄 数据库设计

核心表结构（MySQL）：

| 表 | 说明 | 关键约束 |
|---|---|---|
| `t_user` | 用户 | `username`/`phone`/`student_id` 唯一索引，`version` 乐观锁 |
| `t_student` | 学生认证信息 | `student_id`/`user_id` 唯一 |
| `t_task` | 任务 | `version` 乐观锁 |
| `t_task_application` | 任务申请 | `(task_id, applicant_id)` 唯一（防重复申请）|
| `t_task_order` | 任务订单 | `task_id` 唯一 |
| `t_task_evaluation` | 任务评价 | `(order_id, from_uid)` 唯一 |
| `t_task_rank_monthly` | 月度达人榜归档 | `(user_id, month)` 唯一 |
| `t_goods` | 商品 | `version` 乐观锁，`admin_disabled` 管理员下架标记 |
| `t_goods_order` | 商品订单 | `version` 乐观锁，买卖双方逻辑删除 |
| `t_goods_order_item` | 订单商品项（快照）| — |
| `t_goods_evaluation` | 商品评价 | — |
| `t_notification` | 站内通知 | `message_id` 唯一（幂等）|
| `t_notification_retry` | 通知重试表 | — |
| `t_hot_keywords` | 热搜 | `(keyword, record_date)` 唯一 |
| `t_es_sync_retry` | ES 同步重试 | — |
| `t_admin` | 管理员 | `account` 唯一 |

> 主键策略：业务实体采用雪花算法（`IdType.ASSIGN_ID`），workerId 通过 Redis `INCR` 分配，避免多实例冲突；枚举/分类等少量表使用自增主键。

## 🚀 快速开始

### 环境要求

| 环境 | 要求 |
|---|---|
| JDK | 17+ |
| Maven | 3.6+ |
| MySQL | 8.x |
| Redis | 5.x+ |
| RabbitMQ | 3.x（需启用 STOMP 插件：`rabbitmq-plugins enable rabbitmq_stomp`）|
| Elasticsearch | 8.x |
| MongoDB | 4.x+ |
| XXL-JOB | 可选（未部署时关闭 `xxl.job.enabled`）|
| 阿里云 OSS | 需要有效 AK/SK |

### 配置

1. 在项目根目录创建 `.env` 文件（已加入 `.gitignore`，不会提交）：

```bash
DB_PASSWORD=你的数据库密码
REDIS_PASSWORD=你的Redis密码
MONGODB_PASSWORD=你的MongoDB密码
RABBITMQ_PASSWORD=你的RabbitMQ密码
JWT_SECRET=你的JWT密钥
ALIYUN_ACCESS_KEY_ID=你的OSS AK
ALIYUN_ACCESS_KEY_SECRET=你的OSS SK
```

2. 修改 `application.yml` / `application-dev.yml` 中中间件地址为你的环境。

3. 初始化数据库：执行 `src/test/resources/sql/schema.sql`（建库 `campusHelper`）。

### 启动

```bash
# 打包
mvn clean package -DskipTests

# 运行
java -jar target/CampusHelper-0.0.1-SNAPSHOT.jar
```

或在 IDE 中直接运行 `com.rambo.CampusHelperApplication`。

### 接口文档

启动后访问 Knife4j 接口文档：`http://localhost:8080/api/doc.html`

## 📁 目录结构

```
CampusHelper
├── src/main/java/com/rambo
│   ├── CampusHelperApplication.java    # 启动类
│   ├── common/                         # 通用层
│   ├── infrastructure/                 # 基础设施层
│   │   ├── aop/        # 防重复提交、日志切面
│   │   ├── auth/       # JWT、拦截器、认证
│   │   ├── cache/      # 缓存抽象
│   │   ├── config/     # 配置
│   │   ├── messaging/  # 消息系统（RabbitMQ 抽象）
│   │   ├── retry/      # 重试补偿
│   │   ├── search/     # ES 同步
│   │   ├── storage/    # OSS 存储
│   │   └── websocket/  # WebSocket
│   └── module/                         # 业务层（7 个模块）
│       ├── user/  task/  goods/  notification/  chat/  admin/  operationlog/
├── src/main/resources
│   ├── application.yml                 # 主配置
│   ├── application-dev.yml             # 开发环境配置
│   └── logback-spring.xml
├── src/test/java                       # API 集成测试
└── pom.xml
```

## 📄 许可证

[MIT License](./LICENSE)

---

*本项目为个人学习与求职实战项目，独立完成需求分析、数据库设计与后端编码。*
