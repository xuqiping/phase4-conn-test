-- Canonical Document 治理元数据：来源、责任人、权威等级、密级、标签和有效期。
-- knowledge_documents 一行仍代表一份主文档；版本内容继续由 knowledge_document_versions 管理。
ALTER TABLE knowledge_documents
    ADD COLUMN IF NOT EXISTS owner_id BIGINT,
    ADD COLUMN IF NOT EXISTS source_type VARCHAR(64),
    ADD COLUMN IF NOT EXISTS source_uri TEXT,
    ADD COLUMN IF NOT EXISTS source_updated_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS authority_level VARCHAR(32) NOT NULL DEFAULT 'REFERENCE',
    ADD COLUMN IF NOT EXISTS confidentiality_level VARCHAR(32) NOT NULL DEFAULT 'INTERNAL',
    ADD COLUMN IF NOT EXISTS tags JSONB NOT NULL DEFAULT '[]'::jsonb,
    ADD COLUMN IF NOT EXISTS expired_at TIMESTAMPTZ;

-- V17 的 deadline 是同一业务含义，迁移到命名清晰的 expired_at，旧列暂留兼容。
UPDATE knowledge_documents
   SET expired_at = deadline
 WHERE expired_at IS NULL AND deadline IS NOT NULL;

ALTER TABLE knowledge_documents
    ADD CONSTRAINT ck_doc_authority_level
        CHECK (authority_level IN ('OFFICIAL', 'APPROVED', 'REFERENCE', 'UNVERIFIED')),
    ADD CONSTRAINT ck_doc_confidentiality_level
        CHECK (confidentiality_level IN ('PUBLIC', 'INTERNAL', 'CONFIDENTIAL', 'RESTRICTED')),
    ADD CONSTRAINT ck_doc_effective_range
        CHECK (effective_at IS NULL OR expired_at IS NULL OR effective_at < expired_at),
    ADD CONSTRAINT ck_doc_tags_array
        CHECK (jsonb_typeof(tags) = 'array' AND jsonb_array_length(tags) <= 20);

CREATE INDEX idx_doc_effective_window
    ON knowledge_documents(kb_id, effective_at, expired_at)
    WHERE deleted = 0 AND current_version_id IS NOT NULL;
CREATE INDEX idx_doc_authority_confidentiality
    ON knowledge_documents(kb_id, authority_level, confidentiality_level)
    WHERE deleted = 0 AND current_version_id IS NOT NULL;
CREATE INDEX idx_doc_tags_gin ON knowledge_documents USING GIN(tags);

COMMENT ON COLUMN knowledge_documents.owner_id IS '文档业务责任人用户 ID，不等同于上传人';
COMMENT ON COLUMN knowledge_documents.source_type IS '来源类型，例如 UPLOAD、URL、API、SYNC';
COMMENT ON COLUMN knowledge_documents.source_uri IS '来源定位地址，不存密钥或鉴权参数';
COMMENT ON COLUMN knowledge_documents.source_updated_at IS '上游来源最后更新时间，保留原始时区语义';
COMMENT ON COLUMN knowledge_documents.authority_level IS '权威等级：OFFICIAL/APPROVED/REFERENCE/UNVERIFIED';
COMMENT ON COLUMN knowledge_documents.confidentiality_level IS '密级：PUBLIC/INTERNAL/CONFIDENTIAL/RESTRICTED';
COMMENT ON COLUMN knowledge_documents.tags IS '治理标签 JSON 数组，最多 20 个；应用层限制单标签 64 字符';
COMMENT ON COLUMN knowledge_documents.expired_at IS '文档停止参与默认检索的时刻；空表示长期有效';
