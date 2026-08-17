-- ============================================================
-- V134: 项目组 · 权限码 seed（计划5 Step3）
-- project-group:manage —— gated 策略：仅 admin 角色默认持有，
-- 普通 user 由 admin 按需授（同 V54 media:gen / V55 canvas:write / V58 asset:write 先例）。
-- 覆盖：建组/改名/删除/成员增删/限额/重置used/划拨/回收/我的组列表 全端点。
-- 注：组长级资金与成员管理另有 service 层 requireOwner 二层校验（防越权他组）。
-- ============================================================

INSERT INTO permissions (name, code, resource, action) VALUES
    ('项目组管理', 'project-group:manage', 'project-group', 'manage')
ON CONFLICT DO NOTHING;

-- 系统管理员默认持有
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'project-group:manage'
ON CONFLICT DO NOTHING;

-- ============================================================
-- 回滚（rollback）：
-- DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code='project-group:manage');
-- DELETE FROM permissions WHERE code = 'project-group:manage';
-- ============================================================
