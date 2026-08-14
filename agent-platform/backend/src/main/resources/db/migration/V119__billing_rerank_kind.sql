-- Rerank is a first-class billable model call. It uses input-token pricing only.
ALTER TABLE llm_usage_logs DROP CONSTRAINT IF EXISTS chk_usage_kind;
ALTER TABLE llm_usage_logs
    ADD CONSTRAINT chk_usage_kind CHECK (kind IN ('CHAT','EMBED','RERANK','IMAGE','VIDEO'));

ALTER TABLE pricing_rule DROP CONSTRAINT IF EXISTS chk_pricing_kind;
ALTER TABLE pricing_rule
    ADD CONSTRAINT chk_pricing_kind CHECK (kind IN ('CHAT','EMBED','RERANK','IMAGE','VIDEO'));

COMMENT ON COLUMN llm_usage_logs.kind IS '模型大类：CHAT/EMBED/RERANK/IMAGE/VIDEO';
