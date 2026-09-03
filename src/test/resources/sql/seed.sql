-- ============================================================
-- 测试种子数据：执行前需先完成 schema.sql 建表
-- 注意外键依赖顺序：t_user → t_student / t_address / t_task_category / t_goods_category
-- ============================================================

-- 学生种子数据：供 /user/auth 学生认证用例使用（t_student.id 无自增，需显式 id）
INSERT INTO t_student (id, student_id, real_name, major, grade, college, user_id) VALUES
(1, '2023001', '张三', '软件工程', '2023级', '计算机学院', NULL),
(2, '2023002', '李四', '计算机科学与技术', '2023级', '计算机学院', NULL);

-- 任务分类（status 0=启用，id 自增）
INSERT INTO t_task_category (name, description, status) VALUES
('跑腿代取', '代取快递、外卖、文件等', 0),
('学习辅导', '课程辅导、作业答疑', 0),
('闲置转让', '二手物品转让', 0);

-- 商品分类（status 0=正常，id 自增）
INSERT INTO t_goods_category (name, description, status) VALUES
('数码电子', '手机、耳机、电脑周边', 0),
('书籍资料', '教材、笔记、参考书', 0),
('生活用品', '宿舍好物、生活杂物', 0);

-- 管理员种子：超级管理员（密码 admin123456，BCrypt 哈希）
INSERT INTO t_admin (id, account, password, name, phone, status, role, create_time, update_time)
VALUES (1, '13900000001', '$2a$10$ddDD3d0rB2vLB1hwEedw8uaY/p9nrvsFpJk5VGuimalDWK8qYlauC', '系统管理员', '13900000001', 0, 0, NOW(), NOW())
ON DUPLICATE KEY UPDATE account = account;
