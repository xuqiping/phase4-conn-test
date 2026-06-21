# 计划11：配置 doubao-embedding-vision + 前端可换 + embedding 专用测试

> 创建：2026-06-19
> 依据：用户实际 embedding 配置（volcengine doubao-embedding-vision，dim 2048，endpoint `https://ark.cn-beijing.volces.com/api/coding/v3`）
> 关联：`项目工程文档/设计/后续其他功能设计/企业级RAG向量库知识库设计v6.md`、`项目工程文档/项目开发进度/当前项目开发进度-企业级RAG知识库.md`

## 状态：✅ 代码+DB 完成（2026-06-19）

**§后端/前端改动全落地，mvn BUILD SUCCESS，Flyway V22 success=t。**

- ✅ V22：provider endpoint=`.../api/coding/v3`、models=`["doubao-embedding-vision"]`、KB.embedding_model 同步（实测落地）。
- ✅ `KnowledgeBaseService.DEFAULT_EMBEDDING_MODEL`=`doubao-embedding-vision`。
- ✅ `LlmProviderService.testEmbedding` + `POST /providers/{id}/test-embed`（返回维度）。
- ✅ 前端 `testProviderEmbedding` + `ProviderManageTab` 测试分流。
- ✅ §验证：mvn SUCCESS · psql 断言 endpoint/models/KB 符 · API 实测 doubao-embedding 行 category=EMBEDDING、dim=2048。

**额外（超出本计划范围，顺带做）：**
- V23 `llm_providers.category` 列（CHAT/EMBEDDING/CHAT_EMBEDDING）— 替代前端 `isEmbedding` 正则，测试按 category 分流 + 表格 badge。
- 维度只读展示：`EmbeddingModelVersionMapper` 读 `embedding_model_versions` ACTIVE dim，VO.dim 仅 EMBEDDING 行有值（2048）。

**非代码剩余：**
- admin UI 录 API Key（人工，零密钥进 git）— **唯一待办**。录后 `/test-embed` 真冒烟返维度 2048。
- 路由闭环 `embed("x","doubao-embedding-vision")` 实跑 → 留 IndexJobWorker 阶段（本计划 §不做 边界）。

## Context（为什么做）

用户实际 embedding 模型 = `doubao-embedding-vision`（dim **2048，支持**，与 V17 schema `halfvec(2048)` 对齐，**无需改表**），endpoint `https://ark.cn-beijing.volces.com/api/coding/v3`。

现 V20 seed 是占位：endpoint `/api/v3`、models `["doubao-embedding"]`（Ark 不认此 code）。

**路由陷阱（核心）**：`LlmGateway.findProvider(model)` 按 `provider.models` 列表含 `model` 匹配；且 `OpenAICompatibleProvider` 发给 Ark 的 `model` 字段 = 调用方传入的 code（= `KB.embeddingModel`）字面值。所以必须 **同时** 把 `provider.models` 与 `KB.embeddingModel` 改为真实 code `doubao-embedding-vision`，否则：路由断（models 列表不含旧 code）或 Ark 拒（旧 code 非真实模型）。

**前端"可换"已满足**：`/settings → 全局模型供应商`（`ProviderManageTab.vue`，admin gate `authStore.isAdmin`）已能编辑 name/endpoint/apiKey/models + 测试 + 刷新。**无需新页面**。

**密钥**：AES/GCM 随机 IV，由 `AesEncryptService` 运行时加密，**不能进 git 迁移**。用户选 UI 录入。

用户决策：① API Key 走 UI；② 加 embedding 专用测试（现"测试"走 `chat()`，对纯 embedding provider 必失败）。

## 后端改动

1. **`backend/src/main/resources/db/migration/V22__configure_doubao_embedding_vision.sql`**（新）：
   ```sql
   UPDATE llm_providers
       SET api_endpoint = 'https://ark.cn-beijing.volces.com/api/coding/v3',
           models = '["doubao-embedding-vision"]'
     WHERE name = 'doubao-embedding';
   UPDATE knowledge_bases
       SET embedding_model = 'doubao-embedding-vision'
     WHERE embedding_model = 'doubao-embedding' OR embedding_model IS NULL;
   ```
   - 不动 `embedding_model_versions`（model_code='doubao' 是 RAG 内部向量表注册键，与 provider 路由 code 解耦；表 `knowledge_embeddings_doubao` 固定，Phase1 单表）。

2. **`KnowledgeBaseService.java:25`**：`DEFAULT_EMBEDDING_MODEL` `"doubao-embedding"` → `"doubao-embedding-vision"`（新 KB 路由对）。

3. **embedding 专用测试**：
   - `llm/service/LlmProviderService.java` 加 `public TestConnectionResult testEmbedding(Long id)`：`selectById` → `getDecryptedApiKey(id)` → `llmConfig.createProvider(entity, key)` → `provider.embed("hello", pickFirstModel(entity))` → 成功 `TestConnectionResult`（success=true，model=首模型，message="连接成功 (维度 N)"，durationMs=实测）；失败 `TestConnectionResult.fail(extractRootMessage(e))`。复用现成 `pickFirstModel` / `extractRootMessage` / `llmConfig.createProvider`。
   - `llm/controller/LlmController.java` 加 `@PostMapping("/providers/{id}/test-embed") @RequirePermission("role:manage")` → `providerService.testEmbedding(id)`，返回 `R<TestConnectionResult>`。复用 `TestConnectionResult` DTO（不改类型）。

## 前端改动（复用既有页）

- **`frontend/src/api/llm.ts`**：`llmApi` 加 `testProviderEmbedding(id: number)` → `POST /llm/providers/${id}/test-embed`，复用 `TestConnectionResult` 类型。
- **`frontend/src/components/settings/ProviderManageTab.vue`**：
  - 加 `function isEmbedding(row): boolean { return /embedding/i.test(row.name) || /embedding/i.test(row.models ?? '') }`。
  - `handleTest(id)` 与 `handleTestInModal()`：对 embedding provider 调 `testProviderEmbedding`，其余走原 `testProviderConnection`。成功提示带维度。
  - 表格"测试"按钮 render 不变（仍单按钮，内部分流）。

## 密钥（UI 操作，非代码）

登 admin → 设置 → 全局模型供应商 → 编辑「Doubao Embedding (RAG)」→ API Key 填入 → 保存 → 刷新配置。
`LlmProviderService.update`（`LlmProviderService.java:55-57`）自动 AES 加密 + `llmConfig.reload()` 热生效。**零密钥进 git**。

## 验证

1. `mvn -f backend/pom.xml -DskipTests compile` = BUILD SUCCESS。
2. 起 backend → Flyway V22 → psql：
   ```sql
   SELECT name, api_endpoint, models FROM llm_providers WHERE name='doubao-embedding';
   SELECT name, embedding_model FROM knowledge_bases WHERE deleted=0;
   ```
   断言：endpoint=`.../api/coding/v3`，models=`["doubao-embedding-vision"]`，KB embedding_model=`doubao-embedding-vision`。
3. 前端：admin 登录 → /settings → 全局模型供应商 → 编辑 embedding provider 填 key 保存 → 点「测试」→ 走 test-embed → 成功显「维度 2048」。
4. 路由闭环：`embed("x","doubao-embedding-vision")` 应命中该 provider（worker 阶段实跑验证；当前可临时日志确认 findProvider 命中）。

## 不做（边界）

- 密钥不进迁移（安全）；env 覆盖 / admin API 注入未选。
- `embedding_model_versions` 不动（Phase1 单表，model_code 与路由 code 解耦）。
- 不新建前端页（复用 ProviderManageTab）。
- 阶段2 第4项 IndexJobWorker（真实向量化消费）仍待下一轮——本配置使其前置（endpoint/model）就绪。
