-- ============================================================
-- V79: 日志系统 · 审计查询权限 seed（LOG-FR-12）
-- system:audit:read 仅 admin 默认持有；普通用户查日志中心 → 403。
-- 回滚：
--   DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code='system:audit:read');
--   DELETE FROM permissions WHERE code='system:audit:read';
-- ============================================================

INSERT INTO permissions (name, code, resource, action) VALUES
    ('审计日志查询', 'system:audit:read', 'system', 'audit:read')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'system:audit:read'
ON CONFLICT DO NOTHING;
