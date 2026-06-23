# 计划10：企业级 RAG 知识库（v6 精简版）落地计划

> 创建时间：2026-06-18
> 设计依据：`项目工程文档/设计/后续其他功能设计/企业级RAG向量库知识库设计v6.md`（权威，取代 v3/v4/v5）
> 当前定位：**V17 迁移文件已写好但未提交、未执行，DB 实际只到 V16，无任何 knowledge/rag 表、pgvector 未装；Java `knowledge` 包为零、前端无知识库页面**。本计划 = 把 v6 设计接到现有 backend/frontend 上，runtime-sidecar 不参与。
> 核心原则：**原文为唯一真相源，向量可重建；权限先于检索；引用可追溯；成本硬封顶；流程线性无循环**。能跑、能验收、能演进的 Phase1 最小闭环优先。

---

## 一、当前真实状态（RAG 视角，已实测核对 2026-06-18）

**DB 实况（阶段 0 已完成，2026-06-18）：**

- ✅ pgvector **0.8.2 已装**（andreiramani/pgvector_pgsql_windows 社区预编译，PG16 EDB/MSVC 兼容；`vector.dll`→`lib/`，control+sql→`share/extension/`）。
- ✅ **V17 已执行**：14 张表建成（`knowledge_bases/documents/document_versions/nodes/embeddings_doubao/permissions/index_jobs/reconciliation_reports`、`rag_retrieval_logs/answer_cache/memory_episodes/memory_facts/ingestion_backlog_doubao`、`embedding_model_versions`）。`flyway_schema_history` 记 V17 success=t。
- ✅ HNSW 索引建成：`idx_emb_doubao_hnsw`、`idx_cache_hnsw`（halfvec_cosine_ops）。
- ⚠️ **halfvec 修正**：原 V17 用 `vector(2048)` + HNSW → **pgvector HNSW 硬限 ≤2000 维，2048 建索引必失败**。已改 V17 全部 `vector(2048)`→`halfvec(2048)`、`vector_cosine_ops`→`halfvec_cosine_ops`（halfvec HNSW 上限 4000，2048 容得下，存储减半）。v6 设计 §9.4 同步此修正。
- V17 文件 git 状态：**仍 untracked**（V12–V17 均未提交，只 V1–V11 进了 git）——部署前须提交。

**复用能力（已就绪）：** `llm/LlmGateway` + `OpenAICompatibleProvider`（已支持 Doubao 流式，见 commit `1418ad0`）；`file/FileStorageService`（文件上传）；`@EnableAsync`（异步任务）；`common/*`（`R<T>`/`BaseEntity`/`BusinessException`/`@RequirePermission`）；`auth/security`（JWT/RBAC/Redis 黑名单）。

**缺口（阶段 0 后更新）：**

- DB 层 ✅ 已就绪（pgvector 0.8.2 + V17 + halfvec HNSW）。
- **Embedding 能力不存在**：`LlmProviderInterface` 只有 `chat`/`chatStream`/`supports`，`LlmGateway` 只有 `chat`/`chatStream`——**无 embed 方法**。已配的 doubao provider 是 chat-only（模型 `doubao-seed-2.0-code`，endpoint `/api/coding`），**非 embedding 模型/端点**。RAG 需新增 embedding 接口 + 实现 + gateway 方法 + Doubao embedding 模型配置（独立 model code，复用 Ark API Key）。
  - **进度**：embed 接口 + `OpenAICompatibleProvider` 实现 + `LlmGateway.embed()` + V20 seed（占位）**已完成**（见进度 doc 阶段2）。真实模型配置（V22：endpoint `/api/coding/v3` + `doubao-embedding-vision` + key + 路由修复 + embedding 测试）→ [计划11](计划11-embedding模型配置.md) **已完成**（2026-06-19）。额外 V23 `category` 列 + 维度只读展示。此项勾除。
- Java：**无 `knowledge` 包**，无 entity/mapper/controller/service。
- 前端：**无知识库页面**，无 `api/knowledge.ts`。

**漂移（V17 文件先于 v6 写成，是 v6 超集）：**

| V17 有 / v6 状态 | v6 结论 | 本计划处理 |
|---|---|---|
| `rag_memory_episodes` / `rag_memory_facts`（M1/M2 记忆全栈） | §11 砍/延后 Phase4 | Phase1 **不建 Java 类，表留空不用** |
| `rag_ingestion_backlog_doubao`（M3 active-learning 缺口） | §11 延后 | 同上 |
| `embedding_model_versions` + `knowledge_embeddings_doubao` 分表 | §11 per-model 分表+迁移延后 | Phase1 active 模型固定 doubao，**分表名正好=active 模型**，代码层抽象为单 mapper 指向该表，**不改 schema** |
| `knowledge_document_versions`（版本链） | §6 最小 CAS（`status='STALE'` 软作废） | Phase1 只用节点级 CAS，**不建版本链 Java 逻辑**，表可留作扩展 |
| `knowledge_reconciliation_reports` | §6 最小对账（定时扫 hash 漂移） | Phase1 阶段7 启用 |

> 结论：**不动 V17，避免迁移风险**。Phase1 Java 只映射 v6 子集；`knowledge_embeddings_doubao` 作为 active 单表使用。后续如需严格对齐 v6，再发 V18 精简（DROP 记忆/backlog/模型注册表）。

---

## 二、怎么结合（集成分析）

### 2.1 新建 Java `knowledge` 包（greenfield，映射 v6 §9）

```
com.superprogrammer.knowledge/
  entity/        KnowledgeBase, KnowledgeDocument, KnowledgeNode, KnowledgeEmbedding,
                 KnowledgePermission, KnowledgeIndexJob, RagAnswerCache
  mapper/        各 BaseMapper（knowledge_embeddings 映射 _doubao 表）
  controller/    KnowledgeBaseController   /api/knowledge/bases          (knowledge:read/manage)
                 KnowledgeDocumentController /api/knowledge/documents     (上传/状态)
                 KnowledgePermissionController /api/knowledge/permissions (grant/revoke)
                 KnowledgeRetrieveController /api/knowledge/retrieve      (检索调试，knowledge:read)
                 KnowledgeAskController      /api/knowledge/ask           (RAG 问答，可 SSE)
  service/
    RagRetrievalService      ★ 核心：封装 §6.1 强制约束召回 SQL + 8 步流程，禁止业务绕过
    IndexJobWorker           @Async 拉 outbox job：lock → re-check(content_hash/status/deleted) → embed → DONE，幂等键 I4
    EmbeddingService         经 LlmGateway 调 Doubao embedding（复用，不新造）
    DocumentParserService    Java 基础解析 + L0/L1/L2 生成（section 200-800 tok，L2≤1024）
    VisibilitySetService     Redis vis:{tenant}:{identity}:{kb} → doc_id set，失效事件经 outbox
    AnswerCacheService       per-user 语义缓存（P2 校验链）
    CitationValidator        post-gen 硬校验（A1：[n]∉注入集→拒绝/重生成，零 LLM）
    ReconciliationJob        定时扫 ACTIVE node hash ≠ embedding hash → 补 REINDEX；孤儿向量 DELETE
```

### 2.2 复用现有栈（不重复造轮子）

| 需求 | 复用现有 | 说明 |
|------|---------|------|
| Embedding 调用 | **❌ 需新建**（当前无 embed 能力） | 新增 `LlmProviderInterface.embed()` + `OpenAICompatibleProvider` 实现 + `LlmGateway.embed()` + Doubao embedding 模型配置（endpoint `/api/v3/embeddings`，model code 如 `doubao-embedding-text-*`，复用现有 Ark API Key 加密机制） |
| └ 真实配置 | ✅ 接口+模型配置已落地（2026-06-19） | embed 接口/gateway/V20 seed 完成（进度 doc）；真实配置（V22：endpoint `/api/coding/v3` + model `doubao-embedding-vision` + `KB.embeddingModel` 路由同步 + `/providers/{id}/test-embed` + 前端分流）→ [计划11](计划11-embedding模型配置.md) **已完成**；额外加 V23 `category`(CHAT/EMBEDDING/CHAT_EMBEDDING) 列 + 维度只读展示。**仅 admin 录 API Key 待人工** |
| 生成（RAG 答） | `llm/LlmGateway` `chat`/`chatStream` ✅ | 复用，注入 evidence 拼 prompt；Doubao/DeepSeek/GLM/Kimi 均已配 Key |
| 文档原文上传/存储 | `file/FileStorageService` | 原始文件落本地/对象存储，`file_ref` 回链 |
| 异步索引 worker | `@EnableAsync` + Spring `@Async` | IndexJobWorker 后续可接队列 |
| 权限注解 | `@RequirePermission("knowledge:read/manage")` | auth seed 加 permission 行 |
| 统一响应/异常/审计 | `common/result/R`、`common/entity/BaseEntity`、`common/exception/*` | 所有 entity 继承 BaseEntity |
| 可见集黑名单式 Redis | 现有 Redis(Lettuce) | 新 key 前缀 `vis:` |

### 2.3 与 Chat / Agent / Workflow 集成（v6 §5.1 P4）

RAG 注入点 = **`engine/strategy/*`**，不动 sidecar：

- `ChatSession`（mode=CHAT）：绑 `kbIds` → `DefaultChatStrategy` 调 `RagRetrievalService.retrieve(identity=当前用户, kbIds)` → 证据进 prompt → 网关生成 + Citation 校验。
- `ChatSession`（mode=AGENT）：`AgentRoutingStrategy` 选定 Agent 后，若 Agent.config 含 `kbIds`，检索范围 = **用户权限 ∩ Agent 绑定范围**（P4 求交，任一空→空集）。
- `ChatSession`（mode=WORKFLOW）：触发用户权限 ∩ 工作流/节点绑定 ∩ 节点配置。
- 后台无触发用户：用 service-account 身份权限 ∩ 绑定范围。

**不变式 P4 落单测**：`RagRetrievalService` 入口求交后为空集 → 直接 abstention（"无可检索范围"），不放大、不裸召回。

### 2.4 边界：runtime-sidecar 不参与

RAG = Java 进程内检索 + 生成。sidecar 只管工作流图编排（LangGraph）。若工作流需要"检索节点"，sidecar 遇到该节点 → **回调 Java** `/api/runtime/callbacks/nodes/execute`（现有机制），Java 执行 `RagRetrievalService` 返回输出。**不在 Python 侧引入任何 RAG/向量/LLM 依赖**（sidecar 现 requirements 无 LLM SDK，保持）。

### 2.5 前端

- 新路由 `/knowledge`：KB 管理（CRUD + 权限）、文档上传（拖拽 + 解析进度）、目录树（L0 摘要）、检索调试面板（输入 query 看候选/证据/引用/trace）、RAG 问答区。
- 新 `api/knowledge.ts`（axios + 可选 SSE for ask）、`stores/knowledge.ts`。
- 复用三暗色主题、Naive UI、JWT 注入。

### 2.6 pgvector Windows blocker（阶段 0 必过）

V17 第 19 行 `CREATE EXTENSION IF NOT EXISTS vector`。Windows PG16 需预编译 `vector.dll` 放 `lib/`。**迁移前先手动验证扩展可加载 + HNSW 建成功**，否则整个 V17 阻塞。

---

## 三、开发计划（Phase1 最小闭环）

> 对齐 v6 §10.2 验收 9 条。每阶段可独立验收。Java 代码正常写，遵循项目约定（BaseEntity / R<T> / MyBatis-Plus / Flyway）。

### 阶段 0：blocker 验证 + schema 决策（✅ 已完成 2026-06-18）

- [x] Windows PG16 装 pgvector：社区预编译 `andreiramani/pgvector_pgsql_windows` v0.8.2-pg16（EDB/MSVC 兼容）。`vector.dll`→`lib/`，control+sql→`share/extension/`，重启服务。`CREATE EXTENSION vector` 成功，extversion=0.8.2。
- [x] **发现并修正 HNSW 维度硬限**：pgvector HNSW ≤2000 维，`vector(2048)` 建索引失败 → V17 改 `halfvec(2048)` + `halfvec_cosine_ops`（HNSW ≤4000）。实测通过。
- [x] V17 执行：14 表 + 2 HNSW 索引建成，`flyway_schema_history` 记 V17 success=t（用校准过的 CRC32 手插，与 Flyway 算法一致）。
- [x] 漂移策略确认：保留 V17 超集，Phase1 Java 只映射 v6 子集（记忆/backlog/模型注册表表留空不用）。
- [ ] permission seed（V18）：`knowledge:read` / `knowledge:write` / `knowledge:manage`（阶段 1 一并做）。
  - **建库策略（已锁）：permission-gated**。`knowledge:write` **不**默认给普通用户，由管理员按需授给特定用户/角色。普通用户默认仅 `knowledge:read`（可被授权访问他人库）。seed：`knowledge:read`→普通用户；`knowledge:read/write/manage`→admin。
  - **授权对象（已锁）：USER + ROLE + DEPARTMENT**（v6 §5.1 全范围）。USER/ROLE 复用现有 RBAC；**DEPARTMENT 需新建最小组织模型**（见阶段1）。
- [ ] **待办**：V12–V17 提交 git（当前全 untracked）。

### 阶段 1：knowledge 包骨架 + KB/文档/节点 CRUD + 组织模型（4-5 天）

- [ ] **新建最小组织模型**（DEPARTMENT 授权前置）：`departments` 表（id/name/parent_id/tenant_id + 审计）+ `user_departments`（user_id/department_id），Flyway V18/V19。`DepartmentService` + `/api/departments`（`role:manage` 管理，普通用户只读）。
- [ ] `knowledge/entity/*` + `mapper/*`（含 `KnowledgeEmbeddingMapper` 指向 `knowledge_embeddings_doubao`）。
- [ ] `KnowledgeBaseController`：KB CRUD（`/api/knowledge/bases`），创建须 `@RequirePermission("knowledge:write")`（管理员指定，非全员）。
- [ ] `KnowledgePermissionController`：授权/撤销（`knowledge_permissions`），**grant 须校验调用者对 target 有 `canManage` 或是 owner**；`subject_type` 支持 USER/ROLE/DEPARTMENT。
- [ ] `KnowledgeDocumentController`：上传（走 `FileStorageService`）、状态查询、删除（软删）。
- [ ] 基础 `knowledge_nodes` 目录/章节 CRUD（DIRECTORY/SECTION/TABLE/FAQ，L0/L2）。
- [ ] 单测：CRUD + 权限注解（建库无 `knowledge:write` 被拒）+ grant 鉴权（无 manage 被拒）+ BaseEntity 自动填充。

### 阶段 2：解析 + L0/L1/L2 生成 + IndexJobWorker（5-6 天）

- [x] **新增 embedding 能力（前置，当前无）**：`LlmProviderInterface` 加 `float[] embed(String text, String model)`；`OpenAICompatibleProvider` 实现（POST `{endpoint}/embeddings`，OpenAI 兼容协议，Doubao Ark 支持）；`LlmGateway.embed()`；`llm_providers` 增配 Doubao embedding 模型行（独立 model code，复用 Ark Key + AES 加密）。单测：embed 返回维度=2048。
  - **进度**：embed 接口 + provider 实现 + gateway + V20 seed（占位）**已完成**（见进度 doc 阶段2）。真实模型配置（V22：endpoint `/api/coding/v3` + `doubao-embedding-vision` + key UI 录入 + `KB.embeddingModel` 路由同步 + embedding 专用测试 + 前端可换确认）拆出至 [计划11](计划11-embedding模型配置.md) **已完成**（2026-06-19）。额外：V23 `llm_providers.category`（CHAT/EMBEDDING/CHAT_EMBEDDING）+ 维度只读展示（取 `embedding_model_versions` ACTIVE dim=2048）。**仅 admin 录 API Key 待人工**。
- [x] `DocumentParserService`（✅ 2026-06-19）：Tika 抽正文 → section 切分（200-800 tok，超 800 按子标题切，不足合并）→ L0 摘要（**走 `LlmGateway.chat`**）+ L1 元数据 + L2 切片（≤1024 tok）。3 摘要模式（PER_SECTION/BATCH/HYBRID）。算 `content_hash`。见进度 doc「阶段2 第1项」。
- [x] 写入链路（✅ 2026-06-19，§6 单事务）：`KnowledgeNodeWriter` 写 `knowledge_nodes`（ACTIVE）→ 同事务写 `knowledge_index_jobs` PENDING（仅 L0）。见进度 doc「阶段2 第1项」。
- [x] `IndexJobWorker`（✅ 2026-06-19，BUILD SUCCESS）：`@Scheduled` 轮询（`knowledgeTaskExecutor` 异步消费，非自旋 `@Async`）→ claim（FOR UPDATE SKIP LOCKED）→ **re-check** `content_hash/status/deleted`（I2）不一致作废 → `LlmGateway.embed()` 向量化 L0 摘要 → `completeUpsert` tx 内复校 node + 写 `knowledge_embeddings_doubao`（校 `content_hash=node.content_hash`，I1）→ DONE；幂等（job idempotency_key 唯一 + embedding node_id 唯一 ON CONFLICT，I4）；异常指数退避重试，超 `max_attempt` 置 DEAD。新增 `KnowledgeEmbedding` 实体 + `KnowledgeEmbeddingMapper`（`::halfvec` upsert）+ `HalfVecUtil` + `IndexJobTxService`（短事务）+ `KnowledgeIndexJobMapper.countPendingRunningByDoc` + app `@EnableScheduling`。**阶段2 收口**。详见进度 doc「阶段2 第4项 已落地」。冒烟未跑。
- [ ] 单测：I2 re-check 作废、I4 幂等不重复写向量、并发更新只新版本 job 接管、embed 维度正确。

### 阶段 3：RagRetrievalService 完整 8 步（5-6 天，核心）— ✅ 完成（2026-06-19，BUILD SUCCESS + 冒烟全绿）

- [x] 基础召回方法**强制带 §6.1 WHERE 不变式**（status=ACTIVE / deleted=0 / embed.content_hash=node.content_hash / embedding_model=kb.embedding_model / document_id⊆visible_set / metadata 硬过滤），封装在 `RagRetrievalQueryMapper.denseRecallL0`、**业务层禁绕过**（I1/P1 落该 SQL）。
- [x] step1 可见集加载（DB 计算；阶段 4 完善 Redis）。admin/owner→全库；USER 直接授权展开。
- [x] step3 permission pre-filter + metadata 硬过滤（可见集 ∩ docTypes，禁放大）。
- [x] step4 directory routing（Phase1 无 DIRECTORY 节点 → 降级全库，留 hook；v6 §4 允许）。
- [x] step5 dense recall（query embed vs L0 摘要 HNSW `<=>`，top-40），复用单 query embedding（B4）。
- [x] step6 L2 候选生成 + BM25 预筛（`content_tsv` GIN `@@`+`ts_rank`）+ 父 L0 sim 提权 + **Phase1 rerank 代理**（父 L0 cosine sim + BM25 boost，非 cross-encoder；Phase2 上 bge-reranker-v2-m3）取 top-3。pair ≤ maxRerankPairs=100 断言（B3）。
- [x] step7 abstention：best 父 L0 sim < 0.5 → 固定话术拒答，不编造，记 trace，不写缓存（A2）。
- [x] step8 evidence 装载（`effectiveContextCap` 截断，B1/B2）+ 装载前 content_hash 二次校验（I3，失配丢弃+记 REINDEX）+ 生成 + **Citation 硬校验**（A1，越界重生成一次再败 abstain）。
- [ ] 单测：8 步线性无循环；每个不变式一条机械校验用例（I1/I2/I3/I4/P1/B1-B4/A1/A2/R1）。（**统一留阶段7**，按测试策略）

> 冒烟（smoke-kb kbId=1）：相关 query→带 `[1]` 引用答案+证据；无关 query→abstain LOW_CONFIDENCE（sim 0.17<0.5）；trace 入 `rag_retrieval_logs`。详见进度 doc「阶段3 已落地」。**调参点**：doubao-embedding-vision 相似度绝对值偏低（相关 ~0.50），阈值 0.5 处边界，线上过度 abstain 则下调 `RagConfig.abstainThreshold`。

### 阶段 4：权限可见集 + answer_cache（3-4 天）— 🟡 4-A 可见集完成（2026-06-19）；answer_cache(B) 待做

- [x] `VisibilitySetService`：`vis:{tenant}:USER:{userId}:{kb}` → doc_id set，未命中回源 DB + 写回 + per-key 互斥锁（`ConcurrentHashMap` 锁条带 + double-check）。**可见集 = USER ∪ 其 ROLE ∪ 其 DEPARTMENT**（三层 subject 并集），KB/DIRECTORY/DOCUMENT 展开到 doc_id。详见进度 doc「阶段4-A 已落地」。
- [x] 失效：grant/revoke/doc-delete → **AFTER_COMMIT 发 `VisibilityInvalidationEvent` → listener SCAN+DEL `vis:*:*:{kbId}`**（**非 outbox `visibility_event`**，列保持 unused；单实例 Phase1 够用）。
- [ ] `AnswerCacheService`（B，推后）：step2 缓存短路，`permission_signature=hash(visible_set+kb_scope)`（P3），校验链 `scope_user + permission_signature + evidence content_hash 现值 + evidence doc_id⊆visible_set`（P2），**强制 per-user，跨用户命中禁用**。
- [ ] 重 ACL over-fetch(3-5×) + 调大 `ef_search`，监控 `rag_recall_after_filter`（写 `rag_retrieval_logs`）。
- [ ] 单测：P2 跨用户不命中、文档编辑后 evidence content_hash 逐条 miss。（**统一留阶段7**）

> 4-A 冒烟全绿（KB 级 read→`{"all":true}` 缓存命中；revoke→key 清→403）。**已知 gap（follow-up）**：retrieve 入口 `canRead` 只认 KB 级 grant → DOCUMENT/DIR-only grant 经端点不可达，3 层 DISTINCT 需「visible-set 作单一权限权威」才激活（含 PUBLIC 展开）。

### 阶段 5：Chat / Agent / Workflow 集成（3-4 天）— ✅ 完成（2026-06-20，BUILD SUCCESS）；冒烟 retrieve/ask/CHAT/AGENT 绿 + 3 bug 已修；WORKFLOW(M5) 待跑

- [x] `ChatSession.kb_ids`（BIGINT[]，V25）+ `agent_kb_bindings` / `workflow_kb_bindings` 连表（mirror V16）+ RETRIEVAL 节点类型（V25）。
- [x] `engine/strategy`：CHAT（SYSTEM msg 注入）/ AGENT（firstStepConfigOverride step1 systemPrompt）/ WORKFLOW（检索节点回调 v6 §2.4）调 `RagRetrievalService.retrieveEvidence`，P4 求交落 `RagScopeResolver`（执行身份权限 ∩ 绑定范围，任一空→空集→abstention）。
- [x] `KnowledgeAskController` `POST /api/knowledge/ask`（SSE，复用 chat 流式：retrieveEvidence → chatStream → CITATION → DONE）。
- [x] 引用结构化输出（`[1]..[K]` inline + `EvidenceResult.citations` 供 CITATION 事件；post-gen `CitationChecker` 失效 append disclaimer）。
- [x] B1 修复：`canRead` 委托可见集作单一权限权威（DIRECTORY/DOCUMENT/ROLE/DEPT 可达）。
- [ ] 单测：P4 三身份求交 + 任一空集（**统一留阶段7**）。
- [~] 运行时冒烟：retrieve/ask/CHAT(M1/M2/M3)/AGENT(M4) **绿**（详见进度 doc「冒烟 + 3 bug 修复 + 记忆/trace 端点 已落地」）；冒烟抓出 3 bug 全修：①CHAT kbIds 不落库 ②retrieveEvidence trace `l2_lexical_fallback NOT NULL` ③Agent.config jsonb 写。新增 `/api/chat/memories`（查/删/清空）+ `/api/knowledge/retrieval-logs`（分页查/删/清理）。**待跑**：M5 WORKFLOW RETRIEVAL 节点 + M6 记忆抽取 + 多KB/P4 负例（需无权限用户）。

### 阶段 6：前端知识库页（4-5 天）

> 进度（2026-06-22）：**MVP 完成**（vue-tsc + playwright 浏览器冒烟绿），核心 3 项 + api/store 落地；2 项 defer。详见 `项目开发进度/当前项目开发进度-企业级RAG知识库.md`「〇」节。

- [x] `/knowledge` 路由 + `MainLayout` 侧栏项。
- [x] KB 管理列表/表单/权限授权弹窗。
- [x] 文档上传（拖拽 + 解析/索引进度轮询）。
- [ ] 目录树（L0 摘要展开）+ 文档详情。 — **defer**：后端无 KnowledgeNodeController/端点，需先建。
- [x] 检索调试面板（query → 候选 L0 / 证据 L2 / 引用 / trace / token 预算）。
- [ ] RAG 问答区（SSE 流式，引用点击溯源）。 — **defer**：需写 `/ask` SSE CITATION consumer。
- [x] `api/knowledge.ts` + `stores/knowledge.ts`，复用三暗色主题。

### 阶段 7：一致性对账 + 失效链路 + Phase1 验收（2-3 天）

- [ ] `ReconciliationJob` 定时扫 ACTIVE node hash ≠ embedding hash → 补 REINDEX；孤儿向量 DELETE；落 `knowledge_reconciliation_reports`。
- [ ] 删除链路：node 软删 → 同事务 delete_job → 清向量 + 清 evidence 缓存 + trace 脱敏，各 job 独立重试。
- [ ] 版本最小 CAS：文档更新 = 新 content_hash + `UPDATE...SET status='STALE' WHERE id=? AND status='ACTIVE'`（affected=0 放弃）+ 发旧 DELETE/新 UPSERT job。
- [ ] **按 v6 §10.2 跑 9 条验收**（见 §四）。
- [ ] 浏览器级 E2E：上传→索引→检索→带引用回答→未授权用户检索被拒→缓存 per-user 不泄露→文档更新旧版本不召回。

---

## 四、Phase1 验收清单（v6 §10.2）

1. ✅ 创建知识库、上传文档、看解析与索引状态。
2. ✅ 自动生成 L0 + 文档 L1 + L2，section 200-800 tok，L2≤1024。
3. ✅ 检索默认走 L0 dense + 目录路由；仅必要证据进 L2；L1 仅 outline+importantRules 子集注入。
4. ✅ RAG 回答带引用（文档/章节/页码）。
5. 🟡 未授权用户无法检索（可见集生效）✅ 实测（阶段4-A 冒烟 revoke→403）；**缓存 per-user 不跨权限泄露 ⏳ 待验**（answer_cache 阶段4-B 未建）。
6. ⏳ 文档更新后旧版本不进默认检索（版本 CAS 阶段7 未建）；删文档后不再召回（doc 软删 ✅，但 delete_job 清向量 + 旧版本不召回未验，阶段7）。
7. ✅ worker 失败重试不产生重复向量（幂等键）；并发更新 re-check 不写过期 embedding。
8. ✅ Citation 硬校验拦截伪造引用；top1 分数低正确 abstain 不硬答不编造。
9. ✅ token 上限生效（effectiveContextCap）。

---

## 五、风险与非目标

**Phase1 风险：**

- pgvector Windows 部署（阶段 0 blocker，最优先）。
- HNSW × 强过滤劣化 → 靠 (tenant,kb) partial index 缩空间 + post-ANN 可见集过滤 + over-fetch + ef_search，监控召回。
- Phase1 rerank 用 embedding 相似度临时替代 cross-encoder（精度略降），Phase2 上 bge-reranker-v2-m3。
- 中文 BM25：V17 `content_tsv` 用 `'simple'` 配置，中文分词弱；Phase2 升 zhparser/jieba。

**显式非目标（v6 §11，Phase1 不做）：**

- L3 记忆全栈（episodic/semantic facts/active-learning/个性化/自纠错/治理）→ Phase4。
- CRAG 分档、多跳、HyDE、contextual compression → 延后。
- per-model 分表 + shadow 模型迁移 → Phase1 单模型标量。
- prompt caching、版本链回滚、PII 脱敏、过期降权、连接器、对象存储 → 延后。
- **跨用户语义缓存（永久非目标）**、**记忆作答案事实来源（永久非目标）**、**L2 dense 兜底（永久非目标）**。

---

## 六、相关文档

- 设计权威：`项目工程文档/设计/后续其他功能设计/企业级RAG向量库知识库设计v6.md`
- 通俗解读：`项目工程文档/设计/后续其他功能设计/RAG设计v6-模块作用与通俗解读.md`
- 现有迁移：`backend/src/main/resources/db/migration/V17__create_knowledge_rag.sql`
- 整体架构：`项目工程文档/项目整体说明.md`
- LLM 网关复用：`backend/src/main/java/com/superprogrammer/llm/LlmGateway.java`
