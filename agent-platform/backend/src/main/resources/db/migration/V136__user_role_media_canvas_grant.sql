-- ============================================================
-- V136: user 角色 · 媒体/画布/资产 高成本能力默认放开（16x#2 普通账号 403 修复）
-- 背景：V54 media:gen / V55 canvas:write / V58 asset:write / V87 media:edit 建 permission 时
--   仅授 admin（当时口径「高成本能力，admin 按需授」）。实际产品形态：注册用户即应能
--   图片生成/视频生成/无限画布/资产库写入——普通账号进这些页面全 403（人工测试 16x#2 复报）。
-- 方案：普通 user 角色默认持有四码（计费兜底仍在：积分钱包扣费 + 限流 + 并发闸，
--   成本风险由计费体系承接，不再由 403 挡门）。
-- 幂等：ON CONFLICT DO NOTHING；存量库跑一遍即生效，重复执行零副作用。
-- 注意：权限 baked 进 JWT——已登录用户需重登录才拿到新权限（手册已注明）。
-- ============================================================

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code IN ('media:gen', 'canvas:write', 'asset:write', 'media:edit')
WHERE r.code = 'user'
ON CONFLICT DO NOTHING;

-- ============================================================
-- 回滚（rollback）：恢复「仅 admin」口径——
-- DELETE FROM role_permissions rp
-- USING roles r, permissions p
-- WHERE rp.role_id = r.id AND rp.permission_id = p.id
--   AND r.code = 'user' AND p.code IN ('media:gen','canvas:write','asset:write','media:edit');
-- ============================================================
