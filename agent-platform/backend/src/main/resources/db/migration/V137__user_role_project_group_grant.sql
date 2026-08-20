-- ============================================================
-- V137: user 角色补授 project-group:manage（17x 需求对齐 + 冒烟实测发现）
-- 现状：V134 gated 策略只授 admin → 普通用户 GET /api/project-groups/mine 403，
--   页顶统一入口「参与项目」选择器恒空，组池计费对普通成员不可用；
--   17x 需求原文「个人可创建项目组，并从个人积分中划拨指定积分数到项目组中」
--   ——建组/划拨本就是普通用户功能（组长个人↔组池，动的是本人钱包）。
-- 越权防线不动：组长级资金与成员管理另有 service 层 requireOwner 二层校验
--   （普通用户只能操作自己是组长的组；成员仅看自己的消耗行）。
-- 幂等：ON CONFLICT DO NOTHING（重复执行/新库种子均安全）。
-- ============================================================

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
JOIN permissions p ON p.code = 'project-group:manage'
WHERE r.code = 'user'
ON CONFLICT DO NOTHING;

-- ============================================================
-- 回滚（rollback）：
-- DELETE FROM role_permissions rp
--   USING roles r, permissions p
--   WHERE rp.role_id = r.id AND rp.permission_id = p.id
--     AND r.code = 'user' AND p.code = 'project-group:manage';
-- ============================================================
