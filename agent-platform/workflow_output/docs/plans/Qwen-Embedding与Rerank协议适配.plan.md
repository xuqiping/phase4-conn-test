# Qwen Embedding 与 Rerank 协议适配 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. User has selected inline execution and prohibited subagent use.

**Goal:** 让现有 `qwen3-vl-embedding` 和 `qwen3-rerank` 配置通过平台真实调用，并保持 OpenAI Embedding、模型显式选择、RAG Trace、日志和计费兼容。

**Architecture:** Embedding 在现有 OpenAI Provider 内按显式适配器/兼容端点识别切换请求与响应格式；Rerank 增加独立 DTO、Provider 能力和 `LlmGateway.rerank`，再注入 `ModelRerankProvider`。所有模型仍按类别和模型配置路由，不在运行时代码写死模型 ID。

**Tech Stack:** Java 17、Spring Boot 3.2.5、WebClient、Jackson、JUnit 5、Mockito、MockWebServer、Vue 3、TypeScript、Vitest、Naive UI、PostgreSQL halfvec(2048)。

---

## 文件结构

- 新建 `backend/src/main/java/com/superprogrammer/llm/dto/RerankRequest.java`：网关内部重排请求。
- 新建 `backend/src/main/java/com/superprogrammer/llm/dto/RerankResult.java`：索引、分数、模型、耗时和 usage。
- 修改 `backend/src/main/java/com/superprogrammer/llm/provider/LlmProviderInterface.java`：增加默认 fail-closed 的 rerank 能力。
- 修改 `backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java`：Qwen 多模态 Embedding 与 Rerank HTTP 适配。
- 修改 `backend/src/main/java/com/superprogrammer/llm/config/LlmConfig.java`：增加独立 RERANK 注册表，不进入 Chat。
- 修改 `backend/src/main/java/com/superprogrammer/llm/LlmGateway.java`：增加统一 rerank 出口、Trace、日志、指标和计费。
- 修改 `backend/src/main/java/com/superprogrammer/knowledge/ranking/ModelRerankProvider.java`、`RankingConfiguration.java`：接通真实模型委托。
- 修改 `backend/src/main/java/com/superprogrammer/llm/service/LlmProviderService.java`、`controller/LlmController.java`：真实 Rerank 连通测试。
- 修改 `frontend/src/api/llm.ts`、`components/settings/ProviderManageTab.vue`：调用真实测试并提示固定 2048 维。
- 新增/修改对应后端与前端测试。

### Task 1：Qwen 多模态 Embedding 协议

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java`
- Test: `backend/src/test/java/com/superprogrammer/llm/provider/OpenAICompatibleProviderTest.java`

- [x] **Step 1：写失败测试**

用 MockWebServer 配置 Qwen 多模态端点，断言请求包含：

```json
{"model":"configured-model","input":{"contents":[{"text":"hello"}]},"parameters":{"enable_fusion":true,"dimension":2048}}
```

并返回：

```json
{"output":{"embeddings":[{"index":0,"embedding":[0.1,0.2]}]},"usage":{"input_tokens":1}}
```

测试同时覆盖 OpenAI `data[0].embedding` 旧格式未变化、Qwen 响应缺失、实际维度不等于配置维度时 fail-closed。

- [x] **Step 2：运行测试确认 RED**

Run: `mvn -Dtest=OpenAICompatibleProviderTest test`

Expected: Qwen 测试失败，因为当前请求仍是 `model + input:string`，响应只读 `data[0].embedding`。

- [x] **Step 3：最小实现**

在 Provider 内解析 `config` 的 `adapter` 与 `dimension`；`config` 缺失且端点具有多模态 Embedding 协议特征时兼容识别。Qwen 路径固定要求 2048 维并解析 `output.embeddings[0].embedding`；普通路径保持原逻辑。

- [x] **Step 4：运行测试确认 GREEN**

Run: `mvn -Dtest=OpenAICompatibleProviderTest,LlmProviderServiceTest test`

Expected: 全部 PASS。

- [x] **Step 5：提交**

Commit: `fix(llm): adapt Qwen multimodal embeddings`

### Task 2：Rerank Provider 与网关出口

**Files:**
- Create: `backend/src/main/java/com/superprogrammer/llm/dto/RerankRequest.java`
- Create: `backend/src/main/java/com/superprogrammer/llm/dto/RerankResult.java`
- Modify: `backend/src/main/java/com/superprogrammer/llm/provider/LlmProviderInterface.java`
- Modify: `backend/src/main/java/com/superprogrammer/llm/provider/OpenAICompatibleProvider.java`
- Modify: `backend/src/main/java/com/superprogrammer/llm/config/LlmConfig.java`
- Modify: `backend/src/main/java/com/superprogrammer/llm/LlmGateway.java`
- Modify: `backend/src/main/java/com/superprogrammer/billing/entity/LlmUsageLogEntity.java`
- Test: `backend/src/test/java/com/superprogrammer/llm/provider/OpenAICompatibleProviderTest.java`
- Test: `backend/src/test/java/com/superprogrammer/llm/LlmGatewayTest.java`

- [x] **Step 1：写失败测试**

断言 Rerank 请求包含显式模型、query、documents、`top_n=documents.size()` 与默认 instruct；模拟返回：

```json
{"results":[{"index":2,"relevance_score":0.91},{"index":0,"relevance_score":0.72}]}
```

断言结果保留上游顺序；负例覆盖越界索引、重复索引、空结果和 RERANK 模型找不到。网关测试断言成功/失败分别写 Trace、指标和 `KIND_RERANK` 计费记录，不记录正文。

- [x] **Step 2：运行测试确认 RED**

Run: `mvn -Dtest=OpenAICompatibleProviderTest,LlmGatewayTest test`

Expected: 编译或断言失败，因为 rerank DTO、Provider 方法和网关出口不存在。

- [x] **Step 3：最小实现**

新增 `rerank` 能力；`LlmConfig` 建立 `staticRerankProviders`；`LlmGateway.rerank` 只查 `RERANK` 类别，使用 `callPurpose=RERANK`、候选数量摘要、`KIND_RERANK` 和现有计费 side-channel。

- [x] **Step 4：运行测试确认 GREEN**

Run: `mvn -Dtest=OpenAICompatibleProviderTest,LlmGatewayTest,LlmConfigTest test`

Expected: 全部 PASS。

- [x] **Step 5：提交**

Commit: `feat(llm): add traced rerank gateway`

### Task 3：接入知识库 Ranking 与真实诊断接口

**Files:**
- Modify: `backend/src/main/java/com/superprogrammer/knowledge/ranking/ModelRerankProvider.java`
- Modify: `backend/src/main/java/com/superprogrammer/knowledge/ranking/RankingConfiguration.java`
- Modify: `backend/src/main/java/com/superprogrammer/llm/service/LlmProviderService.java`
- Modify: `backend/src/main/java/com/superprogrammer/llm/controller/LlmController.java`
- Test: `backend/src/test/java/com/superprogrammer/knowledge/ranking/ModelRerankProviderTest.java`
- Test: `backend/src/test/java/com/superprogrammer/knowledge/ranking/RankingEngineTest.java`
- Test: `backend/src/test/java/com/superprogrammer/llm/service/LlmProviderServiceTest.java`

- [x] **Step 1：写失败测试**

构造三个候选，模拟网关返回索引 `[2,0,1]`，断言 ModelRerankProvider 输出 candidateId 对应 `[c3,c1,c2]`；测试连接使用用户提供的固定非敏感案例并返回成功条数/耗时；异常继续 fail-closed。

- [x] **Step 2：运行测试确认 RED**

Run: `mvn -Dtest=ModelRerankProviderTest,RankingEngineTest,LlmProviderServiceTest test`

Expected: 当前 `new ModelRerankProvider(false, null)` 导致失败。

- [x] **Step 3：最小实现**

`RankingConfiguration` 用 `LlmGateway` 构建真实 delegate；新增 `POST /api/llm/providers/{id}/test-rerank`，直接测试指定供应商，避免按模型误路由到其他同名配置。

- [x] **Step 4：运行测试确认 GREEN**

Run: `mvn -Dtest=ModelRerankProviderTest,RankingEngineTest,LlmProviderServiceTest,LlmControllerTest test`

Expected: 全部 PASS。

- [x] **Step 5：提交**

Commit: `fix(rag): connect dedicated rerank provider`

### Task 4：管理员设置页

**Files:**
- Modify: `frontend/src/api/llm.ts`
- Modify: `frontend/src/components/settings/ProviderManageTab.vue`
- Test: `frontend/src/components/settings/ProviderManageTab.test.ts`

- [x] **Step 1：写失败测试**

断言 RERANK 行点击“测试”调用 `/llm/providers/{id}/test-rerank`；Embedding 表单显示“知识库当前固定 2048 维”；成功和失败提示使用安全摘要。

- [x] **Step 2：运行测试确认 RED**

Run: `npm run test -- ProviderManageTab.test.ts`

Expected: Rerank 当前只显示提示，不发请求。

- [x] **Step 3：最小实现**

新增 `testProviderRerank` API；移除 Rerank 占位提示；复用 `runTest` 展示后端 message 与耗时；Embedding 类别下显示固定维度说明。

- [x] **Step 4：运行测试确认 GREEN**

Run: `npm run test -- ProviderManageTab.test.ts`

Expected: PASS。

- [x] **Step 5：提交**

Commit: `fix(settings): test embedding and rerank models`

### Task 5：回归、真实冒烟与文档

> 执行状态（2026-08-14）：定向后端回归 95/95、前端 API 测试和生产构建已通过；两个 Qwen 模型的真实独立调用与日志脱敏检查已通过。全量测试仍含本功能之外的既有失败，因此 Step 1 保持未勾选。Step 3 被本地数据库 `stored_files` 实际字段长度漂移阻断（UUID 文件 ID 写入时报 `varchar(16)` 超长），测试数据已清理，未扩大范围修改旧数据库结构。

**Files:**
- Update: `workflow_output/docs/feature-map/企业级精准知识库RAG.feature-map.md`
- Update: `workflow_output/docs/user-ops/企业级精准知识库RAG用户操作手册.md`
- Create/Update: `workflow_output/开发进度/Qwen-Embedding与Rerank协议适配/README.md`
- Update: `workflow_output/docs/changes/变更记录.md`
- Create: `workflow_output/docs/测试方案/Qwen-Embedding与Rerank协议适配测试方案.md`

- [ ] **Step 1：自动化回归**

Run: `mvn test`

Run: `npm run test`

Run: `npm run build`

Expected: 全部成功，无新警告和敏感信息输出。

- [x] **Step 2：真实模型冒烟**

重启后端加载新代码；管理员登录后分别执行 qwen-embedding 与 qwen-rerank 测试。Embedding 必须返回 2048 维；Rerank 必须把“文本排序模型”相关文档排在量子计算文档之前。

- [ ] **Step 3：知识库链路冒烟**

为测试知识库显式选择 qwen-embedding，重建/新建索引并精准检索；保存 `RERANK + qwen3-rerank` 排序配置后检索，核对 configuredMode/effectiveMode 均为 RERANK，且存在关联 model call。

- [x] **Step 4：日志安全检查**

检查 Java 日志、RAG Trace、计费记录只含关联 ID、模型、供应商、数量、耗时和状态，不含 API Key、query、documents 或 chunk 正文。

- [x] **Step 5：同步文档并提交**

Commit: `docs(rag): document Qwen model adapters`

## 安全检查清单

- [x] API Key 仅从 AES 解密后的运行时对象使用，不写日志/响应/测试快照。
- [x] 上游错误只返回安全摘要；完整响应正文不进入前端、Trace 或业务日志。
- [x] Query、documents、Prompt、Chunk 正文不写日志和 MDC。
- [x] 响应索引、数量、维度严格校验，异常 fail-closed。
- [x] RERANK 不进入 CHAT/EMBEDDING 路由，不允许隐式模型替换。

## 运维考虑清单

- [x] Embedding/Rerank 日志带 traceId、modelRequestId；Ranking 路径带 retrievalRunId/rankingRunId。
- [x] configuredMode 与 effectiveMode 分开记录；fallback 仅按既有配置执行。
- [x] 指标记录 provider/model/result/latency，不使用 query、用户 ID 等高基数标签。
- [x] 计费失败继续吞异常，不回滚已成功的模型响应。
- [x] 管理页真实测试可作为部署后健康检查，不需要导出密钥。
