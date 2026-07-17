-- =============================================================================
-- V15__add_inspiration_review_config.sql
-- 用途：Phase 4 体验优化 - 每日灵感回顾
-- 1. 报告配置增加灵感回顾开关
-- 2. 灵感随记增加复合索引，支持按 reviewed_at / created_at 高效查询
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 报告配置：是否启用每日灵感回顾推送
-- -----------------------------------------------------------------------------
ALTER TABLE report_configs ADD COLUMN IF NOT EXISTS inspiration_review_enabled BOOLEAN NOT NULL DEFAULT FALSE;

-- -----------------------------------------------------------------------------
-- 2. 灵感随记：优化未回顾/最早回顾记录的查询
-- -----------------------------------------------------------------------------
CREATE INDEX IF NOT EXISTS idx_inspiration_notes_user_reviewed_at ON inspiration_notes(user_id, reviewed_at ASC NULLS FIRST, created_at DESC);
