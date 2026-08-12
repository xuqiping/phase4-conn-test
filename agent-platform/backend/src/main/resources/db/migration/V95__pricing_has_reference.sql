-- ============================================================
-- V95: pricing_rule add has_reference (7x-3)
-- ------------------------------------------------------------
-- Purpose: video models can now have DIFFERENT prices for
--   - tasks WITH a reference video (e.g. seeddance 10 CNY / 1M tok)
--   - tasks WITHOUT a reference video (e.g. seeddance 20 CNY / 1M tok)
-- has_reference = FALSE means "no reference video" or "applies to
-- both variants as a fallback" (legacy rows keep old behavior).
-- CHAT/EMBED/IMAGE always have has_reference = FALSE (not meaningful).
-- Backward compatible: NOT NULL DEFAULT FALSE keeps existing rows working.
-- ============================================================

ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS has_reference BOOLEAN NOT NULL DEFAULT FALSE;

-- rebuild lookup index to include the new discriminator
DROP INDEX IF EXISTS idx_pricing_lookup;
CREATE INDEX idx_pricing_lookup
    ON pricing_rule (kind, model, has_reference, effective_from DESC);

COMMENT ON COLUMN pricing_rule.has_reference IS
    'VIDEO only: TRUE = price for tasks with reference video; FALSE = no reference (or fallback).';
