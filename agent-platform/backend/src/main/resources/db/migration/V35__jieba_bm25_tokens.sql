-- V35: RAG 召回升级 Phase2 — 中文 BM25 词法兜底（治 'simple' 不分中文 → l2_lexical_fallback=false）
-- 策略：应用层 jieba 分词、空格拼串写 content_tokens；PG 'simple' 配置按空格切已分好的词 → 真·中文 BM25
-- content_tokens_tsv 为 GENERATED 列（同 content_tsv 模式），写 content_tokens 即自动建索引文本
ALTER TABLE knowledge_nodes ADD COLUMN IF NOT EXISTS content_tokens TEXT;

ALTER TABLE knowledge_nodes
    ADD COLUMN IF NOT EXISTS content_tokens_tsv tsvector
    GENERATED ALWAYS AS (to_tsvector('simple', coalesce(content_tokens, ''))) STORED;

CREATE INDEX IF NOT EXISTS idx_node_tokens_tsv ON knowledge_nodes USING GIN (content_tokens_tsv);
