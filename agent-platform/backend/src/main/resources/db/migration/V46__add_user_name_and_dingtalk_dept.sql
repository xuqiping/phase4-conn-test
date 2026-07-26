-- V46: 用户显示名 + 钉钉部门映射
-- 背景：右上角/用户列表需显示「部门 - 姓名」。钉钉用户 nick 之前丢弃未存；
--      部门体系（departments + user_departments）已存在，缺与钉钉部门 ID 的映射列以支持自动同步。

-- 1) users 加显示名列（钉钉 nick / 真实姓名），区别于登录 username；可空（账密用户未填时回退 username）
ALTER TABLE users ADD COLUMN name VARCHAR(100);

-- 2) departments 加钉钉部门 ID 映射列，用于自动同步钉钉部门时按 dingtalk_dept_id 建/匹配本地部门
ALTER TABLE departments ADD COLUMN dingtalk_dept_id BIGINT;

-- 部分唯一索引：同一租厂下钉钉部门 ID 不重复；NULL 不参与（手动建的部门 dingtalk_dept_id 为 NULL 互不影响）
CREATE UNIQUE INDEX uk_departments_dingtalk_dept
    ON departments (tenant_id, dingtalk_dept_id)
    WHERE dingtalk_dept_id IS NOT NULL;
