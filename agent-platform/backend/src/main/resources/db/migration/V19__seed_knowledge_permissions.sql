-- ============================================================
-- V19: 知识库权限 seed
-- 计划10 阶段1。建库策略 = permission-gated：knowledge:write 不默认给普通用户，
-- 由管理员按需授。普通用户默认仅 knowledge:read（可被授权访问他人库）。
-- ============================================================

-- 19.1 知识库权限
INSERT INTO permissions (name, code, resource, action) VALUES
    ('查看知识库', 'knowledge:read', 'knowledge', 'read'),
    ('创建知识库', 'knowledge:write', 'knowledge', 'write'),
    ('管理知识库', 'knowledge:manage', 'knowledge', 'manage')
ON CONFLICT DO NOTHING;

-- 19.2 普通用户：仅 read（可被授权访问，不能自建）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'user' AND p.code = 'knowledge:read'
ON CONFLICT DO NOTHING;

-- 19.3 Agent 管理员：read + write（管理内容，不能授权）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'agent_admin' AND p.code IN ('knowledge:read', 'knowledge:write')
ON CONFLICT DO NOTHING;

-- 19.4 系统管理员：全部（含 manage，可授权/撤销）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code IN ('knowledge:read', 'knowledge:write', 'knowledge:manage')
ON CONFLICT DO NOTHING;
