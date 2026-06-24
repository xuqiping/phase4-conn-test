-- ============================================================
-- V23: llm_providers.category — 显式区分 CHAT / EMBEDDING / CHAT_EMBEDDING
-- 替代前端 isEmbedding() 正则（靠 name/models 含 embedding 判断，改名即失效）。
-- category 驱动：ProviderManageTab 测试按钮分流（embed vs chat）+ 表格 badge。
-- 取值：CHAT（仅对话）/ EMBEDDING（仅向量）/ CHAT_EMBEDDING（双用，如同一 endpoint 既 chat 又 embed）。
-- 既有行：name/models 含 embedding → EMBEDDING；其余默认 CHAT。
-- ============================================================

ALTER TABLE llm_providers ADD COLUMN category VARCHAR(32) NOT NULL DEFAULT 'CHAT';

UPDATE llm_providers
    SET category = 'EMBEDDING'
  WHERE name ILIKE '%embedding%' OR models ILIKE '%embedding%';
