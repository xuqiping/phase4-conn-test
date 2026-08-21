-- ============================================================
-- V139: 项目组成员权限三维（17x 未解决 #1/#2）
-- 功能：
--   1. project_group_members 加 role（OWNER/MANAGER/MEMBER）——MANAGER 管人不管钱：
--      可邀请/审批/调限额/移除普通成员/看全部产出，不可划拨回收/改组级设置（17x#2 角色维）。
--   2. allowed_kinds JSONB 白名单数组（NULL=不限，[]=全禁）——按成员禁某类模型消耗（17x#2 开关维）。
--   3. member_visibility_overrides JSONB 稀疏覆盖（key=CHAT/EMBED/RERANK/IMAGE/VIDEO，value=OWN/ALL）
--      ——按成员覆盖产出可见性，优先级：成员覆盖 > 组模块覆盖 > 组默认（17x#2 可见性维）。
--   4. uk_pgm_owner 部分唯一索引：每组仅一个活 OWNER（防并发任免出多组长）。
-- 设计要点：
--   - 复活（revive）修 409 在服务层（insertMemberRow 两段式），本迁移只落列；
--     复活时 role/allowed_kinds/member_visibility_overrides 一并重置默认，不继承移除前状态。
--   - jsonb 两列不加 DB CHECK（结构校验代价大），写侧服务层白名单 400，读侧宽容回落
--     （与 V138 module_visibility_overrides 同策略）。
-- 回滚：DROP 索引+约束+列（数据丢失需确认；旧代码不认识新列，PG 加列向后兼容，回滚代码即安全）。
-- ============================================================

-- 1. 成员权限三列
ALTER TABLE project_group_members ADD COLUMN role VARCHAR(16) NOT NULL DEFAULT 'MEMBER';
ALTER TABLE project_group_members ADD COLUMN allowed_kinds JSONB;
ALTER TABLE project_group_members ADD COLUMN member_visibility_overrides JSONB;
ALTER TABLE project_group_members ADD CONSTRAINT ck_pgm_role CHECK (role IN ('OWNER','MANAGER','MEMBER'));

COMMENT ON COLUMN project_group_members.role IS '组内角色（17x#2）：OWNER=组长（建组落，唯一）；MANAGER=管理（管人不管钱）；MEMBER=普通成员';
COMMENT ON COLUMN project_group_members.allowed_kinds IS '成员可用模块白名单 JSONB 数组（CHAT/EMBED/RERANK/IMAGE/VIDEO）；NULL=不限；[]=全禁（仍在组不可消耗）；仅约束组池计费路径，在途任务正常结算';
COMMENT ON COLUMN project_group_members.member_visibility_overrides IS '成员级产出可见性稀疏覆盖 JSONB（key=模块，value=OWN/ALL）；判定优先级：本列 > 组级 module_visibility_overrides > 组级 member_output_visibility；覆盖写在产出归属人行上';

-- 2. 回填：组长行 role=OWNER（须在建唯一索引前，否则多组无 OWNER 不报错但语义缺）
UPDATE project_group_members m SET role = 'OWNER'
    FROM project_groups g
    WHERE m.group_id = g.id AND m.user_id = g.owner_user_id AND m.deleted = 0;

-- 3. 每组唯一活组长（部分唯一索引，V138 uk_pgi_pending 先例）
CREATE UNIQUE INDEX uk_pgm_owner ON project_group_members(group_id) WHERE role = 'OWNER' AND deleted = 0;

-- ============================================================
-- 回滚（rollback）：
-- DROP INDEX IF EXISTS uk_pgm_owner;
-- ALTER TABLE project_group_members DROP CONSTRAINT IF EXISTS ck_pgm_role;
-- ALTER TABLE project_group_members DROP COLUMN IF EXISTS member_visibility_overrides;
-- ALTER TABLE project_group_members DROP COLUMN IF EXISTS allowed_kinds;
-- ALTER TABLE project_group_members DROP COLUMN IF EXISTS role;
--   -- ⚠ 角色/开关/覆盖数据随之丢失，须确认
-- ============================================================
