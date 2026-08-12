-- ============================================================
-- 第二轮 #5：项目记忆公共池
--   owner/admin 可把项目推入公共池（memory_pool_public=true），
--   公共池项目所有人可申请召回授权（复用 user-grants 审批流）。
--   部分索引仅覆盖「已推入公共池且未删」的项目，便于候选列表快速过滤。
-- ============================================================
ALTER TABLE projects ADD COLUMN memory_pool_public BOOLEAN NOT NULL DEFAULT false;

CREATE INDEX IF NOT EXISTS idx_projects_pool_public
    ON projects (memory_pool_public)
    WHERE deleted = 0 AND memory_pool_public = true;
