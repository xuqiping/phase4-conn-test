-- 将 V17 已有的 knowledge_documents 明确作为 Canonical Document，补齐不可变版本治理字段与状态约束。
ALTER TABLE knowledge_document_versions
    ADD COLUMN IF NOT EXISTS file_ref VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS source_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS revoked_by BIGINT,
    ADD COLUMN IF NOT EXISTS replaced_by_version_id BIGINT REFERENCES knowledge_document_versions(id);

-- 旧状态 ACTIVE/STALE/ARCHIVED 归一。先全部降为历史态，再按主文档指针/最新版本选唯一生效项，避免唯一索引冲突。
UPDATE knowledge_document_versions SET status = 'SUPERSEDED' WHERE status = 'ACTIVE';
UPDATE knowledge_document_versions SET status = 'SUPERSEDED' WHERE status = 'STALE';

WITH chosen AS (
    SELECT d.id AS document_id,
           COALESCE(
               (SELECT v.id FROM knowledge_document_versions v
                 WHERE v.id = d.current_version_id AND v.document_id = d.id),
               (SELECT v.id FROM knowledge_document_versions v
                 WHERE v.document_id = d.id ORDER BY v.version_no DESC LIMIT 1)
           ) AS version_id
      FROM knowledge_documents d
     WHERE d.deleted = 0
)
UPDATE knowledge_document_versions v
   SET status = 'EFFECTIVE', effective_at = COALESCE(v.effective_at, now())
  FROM chosen c
 WHERE v.id = c.version_id;

WITH chosen AS (
    SELECT d.id AS document_id,
           (SELECT v.id FROM knowledge_document_versions v
             WHERE v.document_id = d.id AND v.status = 'EFFECTIVE'
             ORDER BY v.version_no DESC LIMIT 1) AS version_id
      FROM knowledge_documents d
     WHERE d.deleted = 0
)
UPDATE knowledge_documents d
   SET current_version_id = c.version_id
  FROM chosen c
 WHERE d.id = c.document_id AND c.version_id IS NOT NULL;

ALTER TABLE knowledge_document_versions
    ADD CONSTRAINT ck_docver_governance_status
    CHECK (status IN ('DRAFT', 'EFFECTIVE', 'ARCHIVED', 'REVOKED', 'SUPERSEDED'));

-- 每个 Canonical Document 最多一个当前生效版本；历史版本仍完整保留。
CREATE UNIQUE INDEX uk_docver_single_effective
    ON knowledge_document_versions(document_id) WHERE status = 'EFFECTIVE';
CREATE INDEX idx_docver_status_time
    ON knowledge_document_versions(document_id, status, created_at DESC);

COMMENT ON TABLE knowledge_documents IS 'Canonical Document 主记录；current_version_id 指向当前生效的不可变版本。';
COMMENT ON TABLE knowledge_document_versions IS 'Canonical Document 的不可变历史版本；切换状态，不覆盖文件和内容 Hash。';
COMMENT ON COLUMN knowledge_document_versions.source_hash IS '版本原始内容 Hash；用于冲突、幂等和删除传播。';
COMMENT ON COLUMN knowledge_document_versions.replaced_by_version_id IS '被哪个新版本替代；便于解释版本沿革。';

-- 回滚应先删除索引/约束，再删除本迁移新增列；生产历史版本数据需先导出，不自动破坏性回滚。
