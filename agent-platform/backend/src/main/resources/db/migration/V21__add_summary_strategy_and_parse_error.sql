-- =====================================================================
-- V21: RAG 阶段2 解析配置 + 解析错误列
-- 配套：项目工程文档/计划/计划10-企业级RAG知识库.md（v6 精简版落地，阶段2 第1项）
--
-- summary_strategy：L0 章节摘要生成模式（KB 级，可由有 knowledge:write 权限用户设置）
--   PER_SECTION = 每 section 一次 LLM 摘要 + 1 次 L1 调用（默认，语义最准）
--   BATCH       = 单次整文档调用批量产出 outline + 各 section 摘要（最省 token）
--   HYBRID      = 单次 L1 调用 + 仅 top-N section 摘要调用（成本折中）
--   取值校验在应用层（KnowledgeBaseService.normalizeStrategy）；
--   项目既有 status/strategy 列均为约定式无 CHECK，保持一致。
--
-- parse_error：文档解析失败原因，status=FAILED 时写入（FAILED 无原因不可调试）。
-- =====================================================================

ALTER TABLE knowledge_bases
    ADD COLUMN summary_strategy VARCHAR(32) NOT NULL DEFAULT 'PER_SECTION';

ALTER TABLE knowledge_documents
    ADD COLUMN parse_error TEXT;
