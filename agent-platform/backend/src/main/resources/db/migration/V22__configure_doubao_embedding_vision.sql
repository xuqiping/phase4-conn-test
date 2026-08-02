-- ============================================================
-- V22: 配置真实 embedding provider — doubao-embedding-vision
-- 计划11。V20 为占位（endpoint /api/v3、models ["doubao-embedding"]，Ark 不认此 code）。
-- 路由陷阱：LlmGateway.findProvider(model) 按 provider.models 列表含 model 匹配；
--   OpenAICompatibleProvider 发给 Ark 的 model 字段 = 调用方传入 code（= KB.embeddingModel）字面值。
--   故 provider.models 与 KB.embeddingModel 必须同步为真实 code doubao-embedding-vision。
-- dim 2048 与 V17 schema halfvec(2048) 对齐，无需改表。
-- 不动 embedding_model_versions（model_code='doubao' 是 RAG 内部向量表注册键，与 provider 路由 code 解耦）。
-- 密钥不进迁移（AES 运行时加密，admin 走 UI 录入）。
-- ============================================================

UPDATE llm_providers
    SET api_endpoint = 'https://ark.cn-beijing.volces.com/api/coding/v3',
        models = '["doubao-embedding-vision"]'
  WHERE name = 'doubao-embedding';

UPDATE knowledge_bases
    SET embedding_model = 'doubao-embedding-vision'
  WHERE embedding_model = 'doubao-embedding' OR embedding_model IS NULL;
