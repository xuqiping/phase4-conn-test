-- ============================================================
-- V145: 大模型配置独立角色（16x 未解决 #2）
--   背景：全局大模型供应商配置（LlmController 全系 + 媒体供应商连通性测试 +
--   系统设置 llm-model-defaults）原本借道 role:manage，导致「配模型的人」
--   必须同时拥有「管角色/管部门/管系统设置」的大权。
--   拍板（16x）：
--     ① 新增独立权限码 llm:config，上述端点全部改挂该码；
--     ② 新增角色 llm_config（大模型配置员）——只持 llm:config，别无他权；
--     ③ admin 【不授予】 llm:config —— 系统管理员从此在「设置」里不能配大模型；
--        价表配置 pricing:manage 仍归 admin（本就独立码，不受影响）。
--   注意：admin 在 V2 的「全量授权」只覆盖当时已存在的码；后续码均显式逐条授，
--   故本码不 INSERT admin 行即等于 admin 天然失去该权限，无需 DELETE。
--
-- 回滚（rollback）：
--   DELETE FROM role_permissions WHERE permission_id IN
--     (SELECT id FROM permissions WHERE code = 'llm:config');
--   DELETE FROM role_permissions WHERE role_id IN
--     (SELECT id FROM roles WHERE code = 'llm_config');
--   DELETE FROM roles WHERE code = 'llm_config';
--   DELETE FROM permissions WHERE code = 'llm:config';
--   （并把代码侧 13 处 @RequirePermission("llm:config") 改回 "role:manage"）
-- ============================================================

-- 1. 新权限码
INSERT INTO permissions (name, code, resource, action) VALUES
    ('配置大模型', 'llm:config', 'llm', 'config')
ON CONFLICT DO NOTHING;

-- 2. 新角色：大模型配置员（专配大模型，无其他作用）
INSERT INTO roles (name, code, description) VALUES
    ('大模型配置员', 'llm_config', '仅配置全局大模型供应商与默认模型，无其他管理权限（16x）')
ON CONFLICT DO NOTHING;

-- 3. 授权：llm_config ← llm:config（admin 刻意不授，见头部拍板③）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'llm_config' AND p.code = 'llm:config'
ON CONFLICT DO NOTHING;
