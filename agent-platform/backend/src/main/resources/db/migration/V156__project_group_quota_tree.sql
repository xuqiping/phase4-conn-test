-- ============================================================
-- V156: 项目组层级额度（17x 未解决：管理可被分配额度，额度树下发）
-- 功能：
--   project_group_members 加 allocated_by_user_id —— 该行额度由谁分配（预算归属上级）。
--   语义：
--     OWNER 行 NULL（组长预算=组池本身，无上级）；
--     组长/admin 给管理/成员配额度 → allocated_by=组长 id（组池直管，不占任何管理预算）；
--     管理给成员配额度 → allocated_by=管理 id（占该管理预算：管理可分配 = 自己额度 − 子树已耗 − 下级预留）。
--   回填：存量非 OWNER 且已配额度行 allocated_by=组长（现状扁平口径等价——过去只有组长能配额度）。
--   部分索引 (group_id, allocated_by_user_id) WHERE deleted=0：子树和查询走它。
-- 设计要点：
--   - 预算不变量（服务层保证）：管理可分配 = quota − (自己已用 + Σ下级已用) − Σ下级 GREATEST(quota−used,0) ≥ 0；
--     下级消耗=预留转已用 1:1，不影响可分配；管理本人消耗/新分配才扣可分配（均持管理行 FOR UPDATE 串行化）。
--   - 管理降回成员时其下级统一改挂组长（reparent），不降预算悬空。
-- 回滚：DROP INDEX idx_pgm_allocated_by; ALTER TABLE project_group_members DROP COLUMN allocated_by_user_id;
--   （数据丢失需确认；旧代码不认识新列，PG 加列向后兼容，回滚代码即安全）
-- ============================================================

ALTER TABLE project_group_members ADD COLUMN allocated_by_user_id BIGINT;

COMMENT ON COLUMN project_group_members.allocated_by_user_id IS '额度分配人（17x 层级额度）：NULL=组长行；组长 id=组池直管；管理 id=占该管理预算（可分配=额度−子树已耗−下级预留）';

-- 回填：存量已配额度非组长行 → 组长（过去仅组长可配额度，扁平口径等价）
UPDATE project_group_members m SET allocated_by_user_id = g.owner_user_id
    FROM project_groups g
    WHERE m.group_id = g.id AND m.quota_limit_points IS NOT NULL AND m.user_id <> g.owner_user_id;

CREATE INDEX idx_pgm_allocated_by ON project_group_members(group_id, allocated_by_user_id) WHERE deleted = 0;
