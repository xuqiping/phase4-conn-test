-- V68: 记忆系统二期 P2 · 项目授权链（memory_project_links，数据层）。
-- 总体设计：项目工程文档/设计/记忆系统二期-项目自动收录与多模态-总体设计.md（P2 项目授权）。
-- 子 plan：workflow_output/docs/plans/记忆系统二期_P2项目授权.plan.md（Step 1）。规格 FR-101~103。
--
-- 语义：child 项目 owner 发起「我的项目条目授权给 parent 项目成员召回」→ parent owner/admin 审批
--   （双向确认 PENDING→ACTIVE/REJECTED，双方均可撤销 ACTIVE→REVOKED）。
--   单级不传递：parent 召回 child 条目，但 parent 的 parent 不会再看到 child（查询只走一跳）。
--
-- 关键设计（plan 坑点规避）：
--   ① 防刷键 (parent,child) 落部分 UNIQUE 行不删（REJECTED 30 天防刷按 created_at 判，软删复活语义：
--      拒绝 30 天后/撤销后再发起 = 同行复活 PENDING 并重置 created_at，不新增行）；
--   ② CHECK parent≠child 防自环；
--   ③ 状态翻转走条件 UPDATE（WHERE status=:expected），并发打不穿（影响行数=0 → 409）。
--
-- 不删语义：撤销=status='REVOKED'（审计留痕），非软删；child 取消自己 PENDING=软删（未生效无审计必要）。

CREATE TABLE memory_project_links (
    id                BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    parent_project_id BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,  -- 被授权方（召回受益项目），随项目死
    child_project_id  BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,  -- 授权方（条目来源项目），随项目死
    granted_by        BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,     -- 发起人（child owner）
    approved_by       BIGINT,                                                                          -- 审批人（parent owner/admin）
    status            VARCHAR(20)              NOT NULL DEFAULT 'PENDING'
                      CHECK (status IN ('PENDING','ACTIVE','REJECTED','REVOKED')),
    approved_at       TIMESTAMP WITH TIME ZONE,                                                      -- 审批时间（通过/拒绝）
    created_by        BIGINT,
    created_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),  -- 发起时间（30 天防刷判据）
    updated_by        BIGINT,
    updated_at        TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted           INT                      NOT NULL DEFAULT 0,
    version           INT                      NOT NULL DEFAULT 0,
    CONSTRAINT chk_memory_project_links_no_self CHECK (parent_project_id <> child_project_id)
);

-- 防刷键：同 (parent,child) 仅一条活行（复活语义保证；软删行不挡）
CREATE UNIQUE INDEX uk_memory_project_links_pair ON memory_project_links(parent_project_id, child_project_id) WHERE deleted = 0;
-- 召回合流主查询：scope 项目作为 parent 查 ACTIVE child 集
CREATE INDEX idx_memory_project_links_parent_status ON memory_project_links(parent_project_id, status) WHERE deleted = 0;
-- 「我授权出去的」列表：child 侧查
CREATE INDEX idx_memory_project_links_child_status  ON memory_project_links(child_project_id, status) WHERE deleted = 0;

COMMENT ON TABLE  memory_project_links IS '项目授权链（二期 P2）：child 条目授权给 parent 成员召回。双向确认 PENDING→ACTIVE/REJECTED；双方可撤 ACTIVE→REVOKED；单级不传递';
COMMENT ON COLUMN memory_project_links.parent_project_id IS '被授权方项目（其成员可召回 child 条目）';
COMMENT ON COLUMN memory_project_links.child_project_id  IS '授权方项目（条目来源；写不穿透：parent 成员对 child 条目零写权）';
COMMENT ON COLUMN memory_project_links.status            IS 'PENDING=待 parent 审批；ACTIVE=生效；REJECTED=被拒（30 天防刷，按 created_at 判）；REVOKED=已撤销（审计留痕行不删）';
COMMENT ON COLUMN memory_project_links.created_at        IS '发起时间；REJECTED 后 30 天内同对重复发起 → 409（复活时重置本字段）';
