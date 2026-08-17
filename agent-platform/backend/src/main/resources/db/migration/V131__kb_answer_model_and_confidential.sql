-- 14x#1 模型可选 + 14x#3 库级保密（知识库模型选择与保密权限 · Step 3/4 单文件两列）
-- answer_model：per-KB RAG 问答 LLM 模型 code。NULL=跟随全局默认（LlmGateway 回退 system_settings 默认对话模型），
--   仅影响问答链路三处 LlmRequest（事实提炼/答案合成/legacy generate）；索引摘要/评测模型维持全局默认（spec §4.2 边界）。
-- confidential：库级保密开关（Step 4 使用，先随迁移落列）。存量库全部 FALSE，行为零变化；
--   开启后非 owner/admin 成员仅保留 RAG 问答唯一内容出口（列表剔 fileRef / asset 403 / nodes 403 / retrieve 403）。
ALTER TABLE knowledge_bases ADD COLUMN answer_model VARCHAR(128);
COMMENT ON COLUMN knowledge_bases.answer_model IS 'per-KB RAG 问答模型 code（llm_providers 模型标识），NULL=跟随全局默认';

ALTER TABLE knowledge_bases ADD COLUMN confidential BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN knowledge_bases.confidential IS '库级保密开关：TRUE 时非 owner/admin 仅 RAG 问答出口，旁路接口 403';
