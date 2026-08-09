-- V70: 记忆系统二期 P4 · 项目总结共享化 + 条目级覆盖表（数据层）。
-- 总体设计：项目工程文档/设计/记忆系统二期-项目自动收录与多模态-总体设计.md §3.6（总结共享）。
-- 子 plan：workflow_output/docs/plans/记忆系统二期_P4总结共享.plan.md（Step 1，FR-301/FR-305）。
--
-- 变更：
--   ① memory_summaries 加 scope_owner（USER/PROJECT，默认 USER 兼容老行——老个人总结语义不变）
--      + source_entry_ids BIGINT[]（条目级 provenance，FR-305「总结记录条目来源」）；
--   ② memory_summaries.user_id 放开 NOT NULL——项目共享总结是项目资产（scope_owner=PROJECT 时
--      user_id=NULL），老行 user_id 非空语义不变（坑点预判①：一期「总结恒只读自己」按 scope_owner 分流）；
--   ③ 新建 memory_entry_coverage：条目级覆盖表（FR-305）。turn 级 memory_summary_coverage 不动
--     （坑点预判②：双表并存，个人总结召回判覆盖仍走老表，条目侧查新表，互不改坏）。
--
-- 偏离 plan：plan 写 UNIQUE(entry_id, project_id)——条目可带多 tag，总结按 (tag,scope) 分组压缩，
--   同条目会被多个 tag 总结吃进；成员个人压缩通道（FR-302）覆盖须按成员各自幂等。
--   故唯一键定为 (entry_id, project_id, tag_id, user_id) NULLS NOT DISTINCT
--   （user_id NULL=项目共享总结覆盖行；非空=成员个人压缩覆盖行），语义同 V47 uk_memory_coverage 范式。

-- ============================================================================
-- 1. memory_summaries 扩列：scope_owner + source_entry_ids；user_id 可空
-- ============================================================================
ALTER TABLE memory_summaries ADD COLUMN scope_owner VARCHAR(10) NOT NULL DEFAULT 'USER';
ALTER TABLE memory_summaries ADD CONSTRAINT chk_memory_summaries_scope_owner CHECK (scope_owner IN ('USER', 'PROJECT'));
ALTER TABLE memory_summaries ADD COLUMN source_entry_ids BIGINT[] NOT NULL DEFAULT '{}';
ALTER TABLE memory_summaries ALTER COLUMN user_id DROP NOT NULL;

-- 项目共享总结读取主查询（成员读 scope_owner=PROJECT 行）
CREATE INDEX idx_memory_summaries_proj_scope ON memory_summaries(project_id, scope_owner) WHERE deleted = 0;
-- 条目级级联查（删条目 → source_entry_ids @> [E] 波及总结标 STALE）
CREATE INDEX idx_memory_summaries_source_entries ON memory_summaries USING gin (source_entry_ids);

COMMENT ON COLUMN memory_summaries.scope_owner       IS 'USER=个人总结（只读自己，一期语义不变）/ PROJECT=项目共享总结（项目资产，全员可读，owner/admin 可写）';
COMMENT ON COLUMN memory_summaries.source_entry_ids  IS '条目级 provenance（memory_project_entries id 集）；删条目级联标 STALE 用；与 source_turn_ids 并存';
COMMENT ON COLUMN memory_summaries.user_id           IS '作者；scope_owner=PROJECT 时可为 NULL（项目资产非个人资产）';

-- ============================================================================
-- 2. memory_entry_coverage：条目级覆盖表（总结吃进哪些条目，幂等判定依据）
-- ============================================================================
CREATE TABLE memory_entry_coverage (
    id          BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    entry_id    BIGINT                   NOT NULL REFERENCES memory_project_entries(id) ON DELETE CASCADE,  -- 被吃进的条目，随条目死
    summary_id  BIGINT                   NOT NULL REFERENCES memory_summaries(id) ON DELETE CASCADE,        -- 吃进的总结，随总结死
    project_id  BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,                -- 总结 scope 项目（非条目来源项目！嵌套取数时条目可来自 child）
    tag_id      BIGINT                   NOT NULL REFERENCES memory_tags(id) ON DELETE CASCADE,             -- 同 (tag,scope) 分组压缩
    user_id     BIGINT                   REFERENCES users(id) ON DELETE CASCADE,                            -- NULL=项目共享总结覆盖；非空=成员个人压缩（FR-302）各自幂等
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    -- 同条目同 tag 同 scope 同主体只一行；NULLS NOT DISTINCT 让 user_id=NULL（共享）约束生效（V47 范式）
    CONSTRAINT uk_memory_entry_coverage UNIQUE NULLS NOT DISTINCT (entry_id, project_id, tag_id, user_id)
);

CREATE INDEX idx_memory_entry_coverage_entry   ON memory_entry_coverage(entry_id);
CREATE INDEX idx_memory_entry_coverage_summary ON memory_entry_coverage(summary_id);
CREATE INDEX idx_memory_entry_coverage_proj    ON memory_entry_coverage(project_id, user_id);

COMMENT ON TABLE  memory_entry_coverage IS '条目级覆盖表（P4 FR-305）：项目总结吃进哪些 memory_project_entries。user_id NULL=项目共享总结；非空=成员个人压缩通道各自幂等。无 deleted——随 entry/summary 级联清';
COMMENT ON COLUMN memory_entry_coverage.project_id IS '总结 scope 项目（非条目来源项目）：嵌套取数（FR-303）时条目可来自 ACTIVE child 项目，覆盖行挂在父 scope 下';
