-- ============================================================
-- V142: 反馈中心 · 权限码 seed（19x，计划 Step1）
--   feedback:manage —— 建议审核 + 提问回答/关闭（审核岗）
--   help:manage     —— 帮助文章 CRUD + 发布/下架（内容岗）
-- 双码分离：审核与内容管理可分给不同 admin 子账号（安全清单「鉴权」条）。
-- 模式同 V134（project-group:manage）：gated，仅 admin 角色默认持有。
--
-- 回滚（rollback）：
--   DELETE FROM role_permissions WHERE permission_id IN
--     (SELECT id FROM permissions WHERE code IN ('feedback:manage','help:manage'));
--   DELETE FROM permissions WHERE code IN ('feedback:manage','help:manage');
-- ============================================================

INSERT INTO permissions (name, code, resource, action) VALUES
    ('反馈管理', 'feedback:manage', 'feedback', 'manage'),
    ('帮助文章管理', 'help:manage', 'help', 'manage')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code IN ('feedback:manage','help:manage')
ON CONFLICT DO NOTHING;
