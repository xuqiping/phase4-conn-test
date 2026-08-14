-- V122__ai_security.sql —— 安全体系 S3 · AI 安全基线（SEC-FR-051 / SEC-FR-056）
-- 1) KB 文档隔离（LLM01 入库面）：命中提示注入特征的文档置 status=QUARANTINED 并记原因，
--    不进检索索引；管理员复核后可解除（置回 PENDING 重解析）。纯加列，可回滚（DROP COLUMN）。
ALTER TABLE knowledge_documents ADD COLUMN IF NOT EXISTS quarantine_reason VARCHAR(255);

-- 2) 会话维度 token 上限（LLM10）：llm_usage_logs 补 session_id（chat 会话归户，系统调用为 NULL）。
--    partial 索引只索引有会话的行（系统调用量大且永不按会话查，省空间）。
ALTER TABLE llm_usage_logs ADD COLUMN IF NOT EXISTS session_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_llm_usage_logs_session ON llm_usage_logs (session_id) WHERE session_id IS NOT NULL;
