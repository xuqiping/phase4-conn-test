-- ============================================================
-- V147: 内置大模型配置员账号（zgdx18158690628）
--   拍板（2026-08-23）：
--     ① 预置一个专管大模型配置的账号，部署即自动创建（跟随 Flyway）；
--     ② 该账号持 llm_config 角色（V145，只含 llm:config 一个权限码）——
--        登录后只能进「设置 → 全局模型供应商 / 安全设置」；
--     ③ llm_config 角色 + llm:config 权限码在角色管理/权限配置 UI 隐藏
--        （代码侧列表过滤，DB 行保留），任何人无法从界面分配/摘除/改权。
--   密码：bcrypt(10) 落库，与 V2 admin 同一姿势；明文不进库。
--   注意：username 有唯一索引，重复执行 ON CONFLICT 跳过，幂等。
--
-- 回滚（rollback）：
--   DELETE FROM user_roles WHERE user_id IN
--     (SELECT id FROM users WHERE username = 'zgdx18158690628');
--   DELETE FROM users WHERE username = 'zgdx18158690628';
-- ============================================================

-- 1. 内置账号（密码：!Aa64221886 的 bcrypt hash）
INSERT INTO users (username, password, email, status) VALUES
    ('zgdx18158690628', '$2b$10$3F51h8chmytHev9hmFoii..JSd5mFvnsIRho9T.Otdppjg/zypzFu', NULL, 'ACTIVE')
ON CONFLICT (username) DO NOTHING;

-- 2. 授角色：zgdx18158690628 ← llm_config（大模型配置员）
INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'zgdx18158690628' AND r.code = 'llm_config'
ON CONFLICT DO NOTHING;
