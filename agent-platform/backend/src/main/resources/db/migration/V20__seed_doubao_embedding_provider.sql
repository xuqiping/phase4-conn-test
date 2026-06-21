-- ============================================================
-- V20: Doubao embedding provider（RAG 向量化前置）
-- 计划10 阶段2。LlmProviderInterface.embed() 经此 provider 调 Ark OpenAI 兼容 /embeddings。
-- 复用 doubao(chat) 的 Ark API Key（同账号一把 Key 通吃 Ark 所有端点）。
-- ⚠️ models 内 "doubao-embedding" 为占位别名（仅供 gateway 路由匹配 + KB.embeddingModel）。
--    真实使用前，管理员须在 /api/llm/providers 把 models 改为 Ark 推理端点 id（如 ep-xxxxxxxx），
--    并把对应知识库的 embedding_model 改为同一端点 id（阶段2 文档说明）。
-- ============================================================

INSERT INTO llm_providers (name, display_name, protocol, api_endpoint, api_key_enc, models, status, sort_order)
SELECT 'doubao-embedding', 'Doubao Embedding (RAG)', 'OPENAI_COMPATIBLE',
       'https://ark.cn-beijing.volces.com/api/v3',
       api_key_enc,
       '["doubao-embedding"]',
       'ACTIVE',
       5
FROM llm_providers
WHERE name = 'doubao'
ON CONFLICT (name) DO NOTHING;
