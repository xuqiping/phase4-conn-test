-- RAG 答案缓存版本化协议：不同向量空间、重排配置、Pipeline、Prompt、知识快照不得互相命中。
ALTER TABLE rag_answer_cache
    ADD COLUMN ranking_config_version VARCHAR(128) NOT NULL DEFAULT 'legacy-ranking',
    ADD COLUMN pipeline_version        VARCHAR(128) NOT NULL DEFAULT 'legacy-pipeline',
    ADD COLUMN prompt_version          VARCHAR(128) NOT NULL DEFAULT 'legacy-prompt',
    ADD COLUMN knowledge_snapshot      VARCHAR(128) NOT NULL DEFAULT 'legacy-snapshot';

-- HNSW 仍负责向量近邻；该索引先缩小用户和版本协议范围，避免跨配置候选进入复核链。
CREATE INDEX idx_cache_protocol_scope
    ON rag_answer_cache(scope_user_id, key_embedding_model, ranking_config_version,
                        pipeline_version, prompt_version, knowledge_snapshot, status);

COMMENT ON COLUMN rag_answer_cache.ranking_config_version IS '重排配置版本；模式、模型或参数变化即隔离旧缓存。';
COMMENT ON COLUMN rag_answer_cache.pipeline_version IS 'RAG Pipeline 版本；检索编排变化即隔离旧缓存。';
COMMENT ON COLUMN rag_answer_cache.prompt_version IS '答案/证据 Prompt 协议版本；Prompt 变化即隔离旧缓存。';
COMMENT ON COLUMN rag_answer_cache.knowledge_snapshot IS '知识内容快照 Hash；文档或节点变化即隔离旧缓存。';

-- 回滚：DROP INDEX idx_cache_protocol_scope; ALTER TABLE rag_answer_cache DROP 上述四列。
