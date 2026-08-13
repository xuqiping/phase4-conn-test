-- P1 Step 5：索引任务版本指纹。
-- 一行任务必须锁定生成该向量时使用的文档、解析器、分块器、Embedding 与 Pipeline 版本，
-- 防止任务重试期间管理员改配置后，同一个幂等任务产生不同结果。
ALTER TABLE knowledge_index_jobs
    ADD COLUMN IF NOT EXISTS version_id BIGINT,
    ADD COLUMN IF NOT EXISTS parser_version VARCHAR(128),
    ADD COLUMN IF NOT EXISTS chunker_version VARCHAR(128),
    ADD COLUMN IF NOT EXISTS embedding_model VARCHAR(255),
    ADD COLUMN IF NOT EXISTS pipeline_version VARCHAR(128);

COMMENT ON COLUMN knowledge_index_jobs.version_id IS '入队时锁定的不可变文档版本 ID';
COMMENT ON COLUMN knowledge_index_jobs.parser_version IS '生成节点所用解析器版本';
COMMENT ON COLUMN knowledge_index_jobs.chunker_version IS '生成 Chunk 所用分块器版本';
COMMENT ON COLUMN knowledge_index_jobs.embedding_model IS '本任务显式选择的向量模型，不随重试漂移';
COMMENT ON COLUMN knowledge_index_jobs.pipeline_version IS '索引编排协议版本，编排语义变化时必须递增';

CREATE INDEX IF NOT EXISTS idx_job_version_pipeline
    ON knowledge_index_jobs(version_id, pipeline_version)
    WHERE version_id IS NOT NULL;
