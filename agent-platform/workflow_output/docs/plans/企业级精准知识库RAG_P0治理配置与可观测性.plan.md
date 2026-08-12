# 企业级精准知识库 RAG · P0 治理、配置与可观测性计划

> 父计划：[企业级精准知识库RAG.plan.md](企业级精准知识库RAG.plan.md)。只含伪代码。

## 技术坑点预判

| 坑点 | 规避 | 验证 |
|---|---|---|
| 配置拆在常量、KB 文本字段和系统设置中 | 建版本化 Pipeline/Ranking 配置，运行时只读解析后的快照 | 改配置后旧请求仍能追溯旧版本 |
| 异步/SSE 丢 MDC | 使用 TaskDecorator 和显式上下文对象双保险 | 子线程日志包含同一 trace/retrieval/ranking id |
| 缓存跨 embedding/ranking 模型命中 | Key 与 SQL 同时加入模型和配置版本 | 切模型后旧缓存零命中 |
| LLM 模型不可用时被静默替换 | 统一 resolver：KB 覆盖→管理员默认→报错 | 禁用模型后得到明确业务错误 |

## 实现步骤

- [ ] **Step 1：建立 Pipeline、检索运行和模型调用主数据**（进行中）
  - **对应需求**：RAG-FR-08、RAG-FR-09
  - **目标**：每次检索可绑定不可变配置快照，并串起召回、重排和模型调用。
  - **动作**：新增 Pipeline 版本、retrieval run、ranking run、model call、fallback event 表；ID 使用 UUID/identity，JSONB 只存可控摘要；保留现有 `rag_retrieval_logs` 兼容写入。
  - **文件（≤20）**：
    - `backend/src/main/resources/db/migration/V101__rag_pipeline_trace_foundation.sql`：新增表、索引、注释和回滚说明。
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/RagPipelineVersion.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/RagRetrievalRun.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/RagRankingRun.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/entity/RagModelCall.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/mapper/`：为上述实体新增 Mapper。
  - **依赖/并行**：无；P0 首步。
  - **安全检查**：Prompt/候选只存 Hash、数量和脱敏摘要；query 可按现有审计策略脱敏。
  - **验证**：Flyway 集成测试；唯一键、外键、trace 反查和存量日志兼容测试。

- [ ] **Step 2：实现可插拔 Ranking 配置和显式模型解析**
  - **对应需求**：RAG-FR-04、RAG-FR-08
  - **目标**：支持 `LLM/RERANK/DISABLED`，LLM/未来 Rerank 均可选择模型且不硬编码。
  - **动作**：新增 `ranking_config` 版本表；KB 仅引用配置；管理员配置知识库默认值；模型能力增加/复用 `RERANK`；resolver 返回配置快照或明确错误。
  - **文件（≤20）**：
    - `backend/src/main/resources/db/migration/V102__rag_ranking_config.sql`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RankingConfigService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/RankingConfigVO.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAdminController.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeBaseService.java`
    - `backend/src/main/java/com/superprogrammer/llm/service/LlmProviderService.java`
    - `backend/src/main/java/com/superprogrammer/system/controller/SystemSettingController.java`
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/api/system.ts`
    - `frontend/src/components/knowledge/KbFormModal.vue`
    - `frontend/src/components/settings/RagRecallSettingsTab.vue`
  - **依赖/并行**：依赖 Step 1。
  - **安全检查**：管理默认配置需 `role:manage`；KB 覆盖配置需 KB 管理权和审计。
  - **验证**：LLM/RERANK/DISABLED 三模式；无默认、模型禁用、显式模型失效都明确报错；页面下拉只显示能力匹配模型。

- [ ] **Step 3：统一 RAG Trace 上下文与 MDC 传播**
  - **对应需求**：RAG-FR-08
  - **目标**：形成 `traceId→retrievalRunId→rankingRunId→modelRequestId→providerRequestId`。
  - **动作**：引入请求级 `RagTraceContext`；控制器创建 trace，编排器创建 run；每批模型调用创建 request；异步任务传递上下文；Java 日志携带用途枚举。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/trace/RagTraceContext.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/trace/RagTraceService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAskController.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeRetrieveController.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/config/KnowledgeTaskExecutorConfig.java`
    - `backend/src/main/java/com/superprogrammer/llm/LlmGateway.java`
    - `backend/src/main/java/com/superprogrammer/billing/service/LlmBillingService.java`
  - **依赖/并行**：依赖 Step 1；可与 Step 2 后端配置服务串行实施，避免同时改 Controller。
  - **安全检查**：MDC 不放 Chunk、Prompt、密钥；userId/traceId 不进入 metric tag。
  - **验证**：同步、异步、SSE、异常和降级路径的 MDC/数据库关联测试。

- [ ] **Step 4：管理端检索时间线与双向追溯 API**
  - **对应需求**：RAG-FR-08
  - **目标**：管理员能从 trace 查检索、重排、模型、费用和审计，也能反向定位 Java 日志关联键。
  - **动作**：新增 trace detail 聚合接口；复用 `llm_usage_logs.trace_id` 和审计日志；前端面板显示用途、耗时、模式、降级和供应商 request id，不显示敏感正文。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/controller/RagRetrievalLogController.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalLogService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/RagTraceDetailVO.java`
    - `frontend/src/api/knowledge.ts`
    - `frontend/src/components/knowledge/RetrievalAuditPanel.vue`
    - `frontend/src/components/knowledge/RetrievalDebugPanel.vue`
  - **依赖/并行**：依赖 Step 3。
  - **安全检查**：仅本人可看自身普通 trace；全局明细需日志/知识库管理权限；查看诊断详情写审计。
  - **验证**：正反向关联、旧日志无关联键提示、越权 403、敏感字段扫描。

- [ ] **Step 5：修复缓存模型隔离并引入版本化缓存协议**
  - **对应需求**：RAG-FR-09
  - **目标**：先消除已知跨模型缓存风险，再为后续知识快照和 Ranking 配置留口。
  - **动作**：`searchCandidates` 强制按 `key_embedding_model`；Key 加 permission signature、pipeline/ranking/prompt/knowledge snapshot；命中后复核权限、状态和 Hash；变更事件主动失效。
  - **文件（≤20）**：
    - `backend/src/main/java/com/superprogrammer/knowledge/mapper/RagAnswerCacheMapper.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/service/internal/AnswerCacheService.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/dto/CacheCandidateRow.java`
    - `backend/src/main/java/com/superprogrammer/knowledge/event/VisibilityInvalidationListener.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/mapper/RagAnswerCacheMapperIT.java`
    - `backend/src/test/java/com/superprogrammer/knowledge/service/internal/AnswerCacheServiceTest.java`
  - **依赖/并行**：依赖 Step 2 的配置版本语义。
  - **安全检查**：保持 per-user 和 permission signature；权限变化立即失效。
  - **验证**：同 query 不同 embedding/ranking/pipeline 不互命中；撤权、撤销、Hash 变化不命中。

## 运维考量

- **做**：配置版本、Trace 指标、失败/降级计数、诊断权限、缓存命中率。
- **做**：Ranking 高精度模式和诊断采样开关；配置变更审计。
- **做**：迁移只新增，支持删除新表/解除引用的回滚顺序。
- **后续**：专用 Rerank 供应商实现放 P3；P0 只建立契约和配置。

## 验证命令

- `cd backend; mvn -Dtest=RagAnswerCacheMapperIT,AnswerCacheServiceTest,RagConfigTest test`
- `cd frontend; npm run test -- --run KbFormModal RagRecallSettingsTab RetrievalAuditPanel`
- `cd frontend; npm run build`
