-- 结构化解析产物存文件存储，版本表只保存可追溯引用、协议版本和完整性 Hash。
ALTER TABLE knowledge_document_versions
    ADD COLUMN IF NOT EXISTS parser_version VARCHAR(64),
    ADD COLUMN IF NOT EXISTS parse_artifact_ref VARCHAR(1024),
    ADD COLUMN IF NOT EXISTS parse_artifact_hash VARCHAR(128),
    ADD COLUMN IF NOT EXISTS parsed_at TIMESTAMPTZ;

COMMENT ON COLUMN knowledge_document_versions.parser_version IS '生成结构化解析产物的解析器版本；用于重解析和幂等判断。';
COMMENT ON COLUMN knowledge_document_versions.parse_artifact_ref IS '结构化解析 JSON 的文件存储引用；大对象不直接写数据库。';
COMMENT ON COLUMN knowledge_document_versions.parse_artifact_hash IS '结构化解析 JSON 的 SHA-256；读取时可校验完整性。';
COMMENT ON COLUMN knowledge_document_versions.parsed_at IS '该版本最近一次成功生成结构化解析产物的时间。';
