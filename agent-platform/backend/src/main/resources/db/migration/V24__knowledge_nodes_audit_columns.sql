-- =====================================================================
-- V24: knowledge_nodes 补审计列 created_by / updated_by
--   V17 建表时漏建（knowledge_bases/knowledge_documents 均有，唯 nodes 遗漏）。
--   KnowledgeNode 继承 BaseEntity，MyBatis-Plus 写入带 created_by/updated_by，
--   缺列导致 INSERT 报 "knowledge_nodes 的 created_by 字段不存在"（冒烟 2026-06-19 发现）。
--   对齐 CLAUDE.md 审计约定（created_by/updated_by 自动填充）。
-- =====================================================================
ALTER TABLE knowledge_nodes ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE knowledge_nodes ADD COLUMN IF NOT EXISTS updated_by BIGINT;
CREATE INDEX IF NOT EXISTS idx_node_created_by ON knowledge_nodes(created_by) WHERE deleted = 0;
