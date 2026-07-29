-- ============================================================================
-- V52 · 计划12 生命周期写侧 hook 配套：回填 memory_project_members
-- ----------------------------------------------------------------------------
-- 背景：写侧 hook（ProjectService create/addMember 同步新栈成员行）上线前的存量
-- 项目从未同步过 memory_project_members——旧栈 project_members 有行、新栈为空，
-- 导致 roster / gen 矩阵 / 召回 ACL（owner 兜底）拿不到成员。
-- 本迁移一次性把旧栈有效成员（未软删 + 项目未软删）回填为新栈 ACTIVE 行。
--
-- 角色映射：OWNER→OWNER，其余（EDITOR/VIEWER）→MEMBER
-- （新栈 ADMIN 是 ACL 配权层，不自动授予，recall_admin 默认 false）。
-- ON CONFLICT DO NOTHING：已同步过的行（hook 上线后新建的）不动。
-- ============================================================================

INSERT INTO memory_project_members (project_id, user_id, role, recall_admin, status, created_at, updated_at)
SELECT pm.project_id,
       pm.user_id,
       CASE WHEN pm.role = 'OWNER' THEN 'OWNER' ELSE 'MEMBER' END,
       false,
       'ACTIVE',
       NOW(),
       NOW()
FROM project_members pm
JOIN projects p ON p.id = pm.project_id AND p.deleted = 0
WHERE pm.deleted = 0
ON CONFLICT (project_id, user_id) DO NOTHING;
