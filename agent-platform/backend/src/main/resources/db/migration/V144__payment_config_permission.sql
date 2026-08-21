-- ============================================================
-- V144: 支付渠道配置 · 权限码 seed（7x 追加）
--   payment:config —— 支付渠道密钥的查看（脱敏）与保存（admin 钱袋子钥匙，独立码可细分授权）
-- 模式同 V142（feedback:manage/help:manage）：gated，仅 admin 角色默认持有。
--
-- 回滚（rollback）：
--   DELETE FROM role_permissions WHERE permission_id IN
--     (SELECT id FROM permissions WHERE code = 'payment:config');
--   DELETE FROM permissions WHERE code = 'payment:config';
-- ============================================================

INSERT INTO permissions (name, code, resource, action) VALUES
    ('支付渠道配置', 'payment:config', 'payment', 'config')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'payment:config'
ON CONFLICT DO NOTHING;
