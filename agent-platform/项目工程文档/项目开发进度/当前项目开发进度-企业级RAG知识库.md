# 当前项目开发进度 — 企业级 RAG 知识库

> 创建：2026-06-18
> 依据：`项目工程文档/计划/计划10-企业级RAG知识库.md`（v6 精简版落地）
> 测试策略：**全部单测/集成测统一留到阶段7收尾**（见末尾「测试策略」），开发期以 mvn compile + DB 实测 + 冒烟为准。

---

## 〇、最新进度速览（handoff，2026-06-19）

- ✅ **阶段2 第1项完成**：`DocumentParserService` + `KnowledgeNodeWriter` + `KnowledgeIndexJob` 实体/mapper + 写入链路（§6 单事务）。`mvn compile` BUILD SUCCESS。**冒烟未跑**（需起 backend + Ark key）。详见下「阶段2 第1项 已落地」。
- ✅ **计划11 完成（2026-06-19，BUILD SUCCESS）**：embedding 真实配置落地。V22 迁移（provider→`/api/coding/v3`+models `["doubao-embedding-vision"]`，KB.embeddingModel 同步）+ `KnowledgeBaseService.DEFAULT_EMBEDDING_MODEL` 改 `doubao-embedding-vision` + 后端 `testEmbedding`（`/providers/{id}/test-embed`，返回维度）+ 前端 `testProviderEmbedding` + `ProviderManageTab` 按 name/models 含 embedding 分流测试。详见下「计划11 已落地」。**key 走 UI（admin 录入）仍待人工**。
- ✅ **阶段2 第4项完成（2026-06-19，BUILD SUCCESS + 冒烟全绿）**：`IndexJobWorker`（`@Scheduled` 轮询 + `knowledgeTaskExecutor` 异步消费）+ `IndexJobTxService`（claim/complete/void/fail 短事务）+ `KnowledgeEmbedding` 实体 + `KnowledgeEmbeddingMapper`（ON CONFLICT upsert `::halfvec`）+ `HalfVecUtil` + `KnowledgeIndexJobMapper.countPendingRunningByDoc` + app `@EnableScheduling`。I2 re-check + I1 tx 内复校 + I4 node_id 唯一 + 指数退避 DEAD + doc 全 DONE→INDEXED。**冒烟已验**：上传 md→解析→向量→doc INDEXED + 向量行 dim=2048 + I1 hash 全等。冒烟抓出 2 bug 已修（V24 补 knowledge_nodes 审计列；mapper 删 updated_at）。详见下「阶段2 第4项 已落地」。**阶段2 收口**。
- ✅ **阶段3 完成（2026-06-19，BUILD SUCCESS + 冒烟全绿）**：`RagRetrievalService` 完整 8 步线性检索（§6.1 强制召回 WHERE + §12 不变式）+ `RagRetrievalQueryMapper`（halfvec `<=>` dense / BM25 `@@` / 可见集 / I3 / L1 SQL）+ `CitationChecker`（A1 确定性）+ `RagConfig`（§7 预算 B1）+ `RagRetrievalLog`/Mapper（trace）+ `KnowledgeRetrieveController`（`POST /api/knowledge/retrieve`）。Phase1 偏离：rerank 用父 L0 cosine 代理、目录降级全库、缓存跳过、可见集 USER 直接。**冒烟全绿**：相关 query→带 `[1]` 引用答案+证据，无关 query→abstain LOW_CONFIDENCE（sim 0.17<0.5），trace 入库。详见下「阶段3 已落地」。
- ✅ **阶段4-A 完成（2026-06-19，BUILD SUCCESS + 冒烟全绿）**：Redis 可见集缓存（USER+ROLE+DEPT 三层并集）+ miss 回源/writeback/per-key 互斥 + grant/revoke/doc-delete AFTER_COMMIT SCAN+DEL 失效。`VisibilitySetService` + `VisibilityQueryMapper`（3 层 EXISTS 并集 + KB 探针）+ `VisibleDocSet` + `VisibilityCacheProperties` + `VisibilityInvalidationEvent`/Listener。接 `RagRetrievalService.step1` + grant/revoke/delete 发事件。**冒烟全绿**：KB 级 read→`{"all":true}` 缓存命中（2nd 检索不重算）；revoke→Redis key 清→403。详见下「阶段4-A 已落地」。answer_cache(step2/B) 推后。**已知 gap**：retrieve 入口 canRead 只认 KB 级 grant → DOCUMENT/DIR-only grant 经端点不可达，3 层 DISTINCT 路径需后续「visible-set 作单一权限权威」才激活。
- ✅ **B1 + 阶段5 完成（2026-06-20，BUILD SUCCESS，冒烟待跑）**：RAG 接进 Chat/Agent/Workflow 三模式 + 修 canRead 权限 gap。详见下「B1 修复 + 阶段5 已落地」。`mvn compile` 全绿；运行时冒烟（起 backend + Ark key + sidecar）待跑。
- ✅ **记忆模式开关 完成（2026-06-20，BUILD SUCCESS + vue-tsc 通过，冒烟待跑）**：4 层 opt-in 开关门控 RAG+记忆+预留缓存。详见下「记忆模式开关 已落地」。
- ✅ **运行时冒烟（部分）+ 3 bug 修复 + 记忆/trace 端点 完成（2026-06-20，BUILD SUCCESS + 冒烟绿）**：起 backend+sidecar+frontend，retrieve/ask/CHAT(M1/M2/M3)/AGENT(M4) 全 PASS。冒烟抓出 3 bug 全修：①CHAT kbIds 不落库→RAG 不可达 ②retrieveEvidence trace `l2_lexical_fallback NOT NULL` 静默丢（/ask 同病）③Agent.config jsonb 写失败（setRagEnabled 500）。新增 `/api/chat/memories`（查/删/清空）+ `/api/knowledge/retrieval-logs`（分页查/删/按时间清理）。详见下「冒烟 + 3 bug 修复 + 记忆/trace 端点 已落地」。
- ✅ **M6 记忆抽取实测 完成（2026-06-20，PASS）**：global ON + CHAT 发「我叫张三，28岁，后端工程师，爱用 Java」→ `extractMemoriesAsync` → `GET /api/chat/memories` 4 行新记忆（FACT name=张三/age=28/occupation=后端工程师 + PREFERENCE favorite_programming_language=Java，全 conf 1.0 source INFERRED，秒级）。负例 PASS：会话 `ragEnabled=false` 覆盖全局 → 抽取跳过（「李四 99 岁」未入库，8 轮 poll 0 新行）→ 门控正确。环境已还原（4 记忆清空 + toggle OFF）。
- ✅ **个人记忆冲突解决 完成（2026-06-21，BUILD SUCCESS + 冒烟全绿）**：embed 聚类分块 + LLM 语义冲突判定 + 会话锁交互式解决（用户 NL 决定保留哪条；无关/超时→FLAGGED 共存可见）。V27（user_memories 加 block_label/embedding/conflict_id + memory_conflicts 表）+ V28（删 V6 unique(user_id,key) 供冲突共存）。2 新端点（`GET /memories/conflicts` + `PUT .../resolve`）。冒烟 3 场景全通（KEEP_NEW 同会话 / FLAGGED 共存 / resolve 端点）。**7 个 dev→runtime bug 全修**（mapper @Param / 列名 key→memory_key / bigint[] 字面量 / 路由 A/B / judge 强化 / 删唯一索引 / resolve PENDING vs FLAGGED 分支）。**refine**：记忆模式 ON 时冲突检测改同步（askText 同轮投递，消竞态），代价 ~20-60s/轮（gate 默认关）。设计见 `设计/后续其他功能设计/个人记忆知识库设计（含记忆冲突解决）.md`，计划+执行结果见 `项目开发进度/当前项目开发进度-个人记忆知识库（含冲突解决）.md`。
- ✅ **M5 WORKFLOW RETRIEVAL 冒烟 完成（2026-06-21，PASS）**：造 workflow（START→RETRIEVAL→END，config kbId:1 query）+ workflow_kb_bindings[1] + rag_enabled=true，`POST /api/workflows/{id}/run` → Java(gateway=sidecar)→sidecar LangGraph→RETRIEVAL 回调 Java `/callbacks/nodes/execute`→`retrieveEvidence(KB1,"如何安装部署系统")`→证据（`[1] 安装步骤 PostgreSQL16/pgvector/SpringBoot8080`）返回→下游 END。8 events 全 EXECUTION_COMPLETED。**抓出 1 bug 修**：sidecar `RuntimeNodeCallbackResponse` 的 `selectedSkillIds`/`stepOutputs`（SKILL 专用）对 RETRIEVAL/AGENT 回调为 null → pydantic `list_type` 校验失败 → EXECUTION_FAILED；加 `@field_validator(before)` null→[]。
- ✅ **answer_cache(B) 完成（2026-06-21，BUILD SUCCESS + 冒烟绿）**：阶段4-B 落地。语义答案缓存接进 retrieve()（单KB debug，存完整 answer）+ retrieveEvidence()（多KB 生产路径 CHAT/AGENT/WORKFLOW/ask，存证据 systemPrompt，不省生成）。HNSW key_embedding 近邻 + per-user 强制 SQL 过滤 + P3 permission_signature(sha256 可见集+kb_scope) + P2a evidence hash 复校。懒失效（无主动 purge：权限变→签名变 miss；doc 删/重传→hash 变 miss）。opt-in 默认关（rag.answer-cache.enabled=false）。`mvn compile` 全绿（IDE 全程 Lombok 误报，BUILD SUCCESS 证实）；运行时冒烟绿（同 query 8121ms→190ms CACHE_HIT ~43x，usage_count++，trace CACHE_HIT 落库；近义 miss 属预期）；冒烟抓出 1 trace bug 已修。详见下「answer_cache(B) 已落地」。
- ⏭️ **下一步**：提交 git（V12–V28 + 全部本次改动，部署前硬门）/ 前端检索节点 UI / 记忆冲突 judge 准确率调优（Phase1 gap）/ answer_cache 运行时冒烟。
- 📌 偏好：所有产出文件写项目内目录（不写 `~/.claude`），见 memory `feedback-files-in-repo`。
- ⚠️ git：V12–V26 全 untracked（含本次 3 bug 修复 + 2 新端点 + 2 DTO/VO），部署前须提交。

---

## 一、已完成

### 阶段 0：基础设施 blocker（✅ 2026-06-18）

- ✅ pgvector **0.8.2** 装入 PG16（EDB/MSVC，社区预编译 `andreiramani/pgvector_pgsql_windows` v0.8.2-pg16）。`vector.dll`→`lib/`，control+sql→`share/extension/`。
- ✅ **发现并修正 HNSW 维度硬限**：pgvector HNSW ≤2000 维，v6/V17 原 `vector(2048)` 建索引必失败 → 改 **halfvec(2048) + halfvec_cosine_ops**（HNSW ≤4000）。实测 PG16/0.8.2 通过。
- ✅ V17 执行：14 张 knowledge/rag 表 + 2 HNSW 索引建成，`flyway_schema_history` 记 V17 success=t（CRC32 校准手插）。
- ✅ 漂移策略确认：保留 V17 超集，Phase1 Java 只映射 v6 子集（memory/backlog/模型注册表表留空不用）。

### 阶段 1：knowledge 包骨架 + KB/文档/节点 CRUD + 组织模型（✅ 2026-06-18，BUILD SUCCESS）

- ✅ **V18** departments + user_departments 表（已跑+验证）。
- ✅ **V19** knowledge:read/write/manage permission seed（已跑+验证：user=read / agent_admin=read+write / admin=all）。
- ✅ 实体 6：Department、UserDepartment、KnowledgeBase、KnowledgeDocument、KnowledgeNode、KnowledgePermission（后者独立无 BaseEntity，撤销=硬删）。
- ✅ Mapper 6、DTO 7、Service 4、Controller 4。
- ✅ 新 API 面：
  - `/api/departments`（CRUD + 成员分配/移除，`role:manage`）
  - `/api/knowledge/bases`（KB CRUD，**permission-gated**：create/update/delete 需 `knowledge:write` + service 校 owner||admin）
  - `/api/knowledge/permissions`（grant/revoke/list，**USER/ROLE/DEPARTMENT**，`assertManage` 校 owner||admin）
  - `/api/knowledge/documents`（上传 FileStorageService+SHA256 状态 PENDING / 列表 / 查 / 软删）
- ✅ mvn compile BUILD SUCCESS。

### 关键设计落实

- 建库 permission-gated：普通用户默认 `knowledge:read`，建库需 `knowledge:write`（管理员授）。
- owner 隐式 manage：`canManage = admin || createdBy`。
- 授权三层主体 USER/ROLE/DEPARTMENT；DIRECTORY/DOCUMENT 继承所属 KB。
- 撤销=硬删（knowledge_permissions 无 deleted/version）。

---

## 二、阶段1 已知边界（后续阶段补）

- ROLE/DEPARTMENT 授权**已存表**，但可见集**聚合解析**（role→用户、dept→用户）在**阶段4** VisibilitySetService 做；当前 `canRead/canWrite` 只解析 USER 直接授权。
- 文档上传只建 PENDING 行，**解析 + 索引在阶段2**。

---

## 三、下一步：阶段 2（进行中）

- [x] **新增 embedding 能力**（2026-06-18）：`LlmProviderInterface.embed()` + `OpenAICompatibleProvider` 实现（绝对 URL `/embeddings`，兼容 Ark `/api/v3` 与 OpenAI `/v1`）；`ClaudeProvider` stub；`LlmGateway.embed()` 双重载。BUILD SUCCESS。
- [x] **V20** seed doubao-embedding provider（复用 Ark Key，endpoint `/api/v3`，models `["doubao-embedding"]` 占位别名，真实用前管理员改 ep-id）。KB 默认 embeddingModel 对齐 `doubao-embedding`。
- [x] **阶段2 第1项 落地（✅ 2026-06-19，BUILD SUCCESS）**：`DocumentParserService` + `KnowledgeNodeWriter`（§6 单事务写入链路）+ `KnowledgeIndexJob` 实体/mapper。详见下「阶段2 第1项 已落地」。
- [x] **embedding 真实配置（✅ 2026-06-19，计划11）**：V22 把 `doubao-embedding` provider 路由 code 改 `doubao-embedding-vision` + endpoint `/api/coding/v3`；KB.embeddingModel 同步；新 KB 默认值对齐；后端 embedding 专用测试 + 前端分流。**仅 key 走 UI 待 admin 录入**。详见下「计划11 已落地」。
- [x] `KnowledgeEmbedding` 实体（标量列，不映射 halfvec）+ `HalfVecUtil`（float[]→`'[..]'`，dim=2048，Locale.US）。— 见「阶段2 第4项 已落地」。
- [x] `IndexJobWorker`（`@Scheduled` 轮询 + `knowledgeTaskExecutor` 异步）：claim（FOR UPDATE SKIP LOCKED）→ re-check content_hash/status/deleted（I2）→ `LlmGateway.embed()` 向量化 L0 → tx 内复校 + 写 `knowledge_embeddings_doubao`（校 content_hash=node，I1）→ DONE；幂等（node_id 唯一，I4）；超 max_attempt 置 DEAD，指数退避重试。— 见「阶段2 第4项 已落地」。

### 阶段2 第1项 已落地（✅ 2026-06-19，`mvn compile` BUILD SUCCESS）

范围 = 解析 + 实体 + 写入链路（**不含 embedding**）。上传 → commit 后异步解析 → 落 `knowledge_nodes`(ACTIVE) + `knowledge_index_jobs`(PENDING)。终态 `EMBEDDING`（待 worker，不达 INDEXED）。

**新增文件（11）：**
- `db/migration/V21__add_summary_strategy_and_parse_error.sql` — KB.`summary_strategy`（PER_SECTION/BATCH/HYBRID）+ document.`parse_error`。
- `knowledge/entity/KnowledgeIndexJob.java` — **非 BaseEntity**（表无 deleted/version）；insert 留 null 走 DB 默认（FieldStrategy.NOT_NULL）。
- `knowledge/mapper/KnowledgeIndexJobMapper.java`
- `knowledge/util/HashUtil.java`（sha256 hex）+ `knowledge/util/TokenEstimator.java`（chars/4 启发式）。
- `knowledge/service/internal/` — `Section` / `ExtractedDocument` / `L1Metadata` / `BatchLlmResult`。
- `knowledge/service/KnowledgeNodeWriter.java` — `@Transactional` 单事务落库。
- `knowledge/service/DocumentParserService.java` — Tika 抽取 + section 切分 + 3 摘要模式 + 状态机。
- `knowledge/event/DocumentUploadedEvent.java` + `knowledge/event/DocumentParseListener.java`（`@TransactionalEventListener(AFTER_COMMIT)` + `@Async("knowledgeTaskExecutor")`）。
- `knowledge/config/KnowledgeTaskExecutorConfig.java`（core2/max4/queue50/CallerRunsPolicy）。

**修改：** `pom.xml`（Tika 2.9.2）、`KnowledgeBase`/`KnowledgeDocument` 实体（`summaryStrategy`/`parseError`）、KB Request/VO/Service（strategy 校验容错，非法→PER_SECTION 不抛 400）、Document VO/Service（`parseError` + upload 发事件）。

**vs 计划的关键修正（实施中发现）：**
1. **writeNodes 拆独立 bean `KnowledgeNodeWriter`** — 同类自调 `@Transactional` 会绕过 Spring 代理（事务不生效），跨 bean 调用才经代理。
2. **状态标记走 `UpdateWrapper`** — 单次解析内多次更新 doc，复用同一内存实体会触发 `@Version` 失配；UpdateWrapper 按 id 直改列绕版本。
3. **L0/L2 对齐 v6 §3.1** — 每 section 一 L0（摘要=content）+ 其 L2 子节点（原文 ≤1024 tok 切片）；文档摘要进 L1。空摘要兜底 = section 原文前 400 字（禁空 content L0，防召回污染）。

**状态机：** PENDING → PARSING → SUMMARIZING → EMBEDDING（待 worker）｜任一异常 → FAILED + `parse_error`。

**未验证：** 冒烟未跑（需起 backend + Ark chat/embedding key 真实配置）。计划 §验证 的 psql 查询待运行环境验：每 section 一 L0、L2.parent_id 全指 L0、`L0.content_hash==sha256(L0.content)`、仅 L0 各 1 条 UPSERT job、无 L2 有 job、doc 终态 EMBEDDING + l1_metadata 非空。

### 计划11 已落地（✅ 2026-06-19，`mvn compile` BUILD SUCCESS）

范围 = embedding 真实配置（endpoint/model/路由/测试），**不含 worker 消费**。使 IndexJobWorker 前置就绪。

**改动文件（6）：**
- `db/migration/V22__configure_doubao_embedding_vision.sql`（新）：`UPDATE llm_providers SET api_endpoint=.../api/coding/v3, models='["doubao-embedding-vision"]' WHERE name='doubao-embedding'`；`UPDATE knowledge_bases SET embedding_model='doubao-embedding-vision' WHERE embedding_model='doubao-embedding' OR NULL`。不动 `embedding_model_versions`（model_code='doubao' 解耦）。
- `knowledge/service/KnowledgeBaseService.java`：`DEFAULT_EMBEDDING_MODEL` `"doubao-embedding"` → `"doubao-embedding-vision"`。
- `llm/service/LlmProviderService.java`：`testEmbedding(Long id)` — `selectById`→`getDecryptedApiKey`→`llmConfig.createProvider`→`provider.embed("hello", pickFirstModel)`，成功 `TestConnectionResult`（message=`连接成功 (维度 N)`，model=首模型，durationMs=实测）；失败 `fail(extractRootMessage)`。复用 `pickFirstModel`/`extractRootMessage`/`createProvider`。
- `llm/controller/LlmController.java`：`POST /api/llm/providers/{id}/test-embed` `@RequirePermission("role:manage")` → `testEmbedding`，`R<TestConnectionResult>`。
- `frontend/src/api/llm.ts`：`llmApi.testProviderEmbedding(id)` → `POST /llm/providers/${id}/test-embed`。
- `frontend/src/components/settings/ProviderManageTab.vue`：`isEmbedding(row)`（name/models 含 embedding）；`handleTest`/`handleTestInModal` 按此分流 → embed 走 `testProviderEmbedding`，成功提示带维度；其余走原 chat 测试。表格按钮 render 不变。

**关键设计落实：** 路由陷阱 — `provider.models` 与 `KB.embeddingModel` 必须**同时**为真实 code `doubao-embedding-vision`（否则 gateway 路由断或 Ark 拒旧 code）。密钥**不进 git**（AES 运行时加密，admin UI 录入，`LlmProviderService.update` 自动加密 + `reload` 热生效）。

**未验证：** Flyway V22 未跑（待起 backend）；psql 校 endpoint/models/KB.embedding_model 未验；前端 test-embed 冒烟未跑（需 admin 录 key）；路由闭环 `embed("x","doubao-embedding-vision")` 命中 provider 待 worker 阶段实跑。**key 录入 = admin UI 人工操作**（非代码）。

**IDE 噪音：** 编辑期间 IDE Lombok 处理器崩溃（`NoClassDefFoundError: lombok.javac.Javac`），大量 getter/builder/log 误报错；`mvn compile` BUILD SUCCESS 证实为误报。

---

### 阶段2 第4项 已落地（✅ 2026-06-19，`mvn compile` BUILD SUCCESS）

范围 = 索引 job 消费者：轮询认领 PENDING/RUNNING(过期) 的 UPSERT job → embed L0 → 写 `knowledge_embeddings_doubao` → DONE，文档全 job 完成置 INDEXED。**阶段2 收口**（上传→解析→落库→向量化 全链路）。

**新增文件（5）：**
- `knowledge/entity/KnowledgeEmbedding.java` — 标量列实体（**不映射 halfvec 列**，表无 deleted/version，非 BaseEntity）。
- `knowledge/util/HalfVecUtil.java` — `toHalfVec(float[])` → `'[v0,v1,...]'`（`%.6f` Locale.US，DIM=2048）。
- `knowledge/mapper/KnowledgeEmbeddingMapper.java` — `upsert(...)`：`#{halfvec}::halfvec` + `ON CONFLICT(node_id) DO UPDATE`（node_id 唯一 → 同 node 就地覆盖）。
- `knowledge/service/internal/IndexJobTxService.java` — `@Transactional` 短事务：`claimBatch`/`completeUpsert`/`voidJob`/`failJob`。独立 bean（避 worker 自调绕代理）。
- `knowledge/service/IndexJobWorker.java` — `@Scheduled(fixedDelayString="${knowledge.index.poll-ms:5000}")` 认领 BATCH=8 → 提交每 job 到 `knowledgeTaskExecutor` 异步 `process`。

**修改：**
- `KnowledgeIndexJobMapper.java` 加 `@Select countPendingRunningByDoc(docId)`（JOIN nodes 取 document_id，判文档是否全完成）。
- `AgentPlatformApplication.java` 加 `@EnableScheduling`（原仅 `@EnableAsync`）。

**不变式落地：**
- **I2 re-check**：embed 前 worker 读 node（null/`@TableLogic` 已滤 deleted/status≠ACTIVE/content_hash≠job → `voidJob` FAILED 作废）；`completeUpsert` tx 内**再读 node 复校**（防 embed 期间变更，复校失败转 `voidJob`）。
- **I1**：`completeUpsert` 校 `node.contentHash == 写入依据 hash` 才 upsert；embedding 行 content_hash 始终 = node.content_hash。
- **I4 幂等**：job.idempotency_key 唯一（writer 保证）+ embedding.node_id UNIQUE → 同 node 重复认领/job 不产生多行向量。
- **DEAD/退避**：claim 时 attempt+1；异常 `failJob`：attempt≥maxAttempt → DEAD，否则 PENDING + 指数退避（`min(10<<shift,300)`s）锁定 `locked_until` 待重认领。
- **doc → INDEXED**：每 job DONE 后 `countPendingRunningByDoc`，为 0 → 文档置 INDEXED（DEAD 容忍：有缺口但其余已索引；缺口由阶段7 对账兜底）。

**关键设计：**
- claim 用 MyBatis-Plus wrapper `.and(分组).last("LIMIT n FOR UPDATE SKIP LOCKED")` 多 worker 安全（现单实例，预留）。
- embed（秒级阻塞+计费）**在事务外**；DB 写全走 `IndexJobTxService` 短事务，不占 DB 连接做 LLM 调用。
- `KnowledgeEmbedding` 不映射 halfvec 列：MyBatis-Plus 默认 handler 不支持 halfvec，走自定义 `@Insert` SQL `#{halfvec}::halfvec`。
- executor 用显式构造器 + `@Qualifier("knowledgeTaskExecutor")`（Lombok `@RequiredArgsConstructor` 不拷贝 @Qualifier，故手写构造器）。

**冒烟已验（✅ 2026-06-19，全链路通过）：** admin 录 key 后起 backend 上传 md → 解析（阶段2 第1项）→ worker 向量化（阶段2 第4项）。psql 实测：
- `knowledge_index_jobs` 全 **DONE**（无 RUNNING/DEAD）；
- `knowledge_documents` 终态 **INDEXED**；
- `knowledge_embeddings_doubao` 行数 = L0 数，`vector_dims(embedding::vector)` = **2048**；
- **I1 验证**：`SELECT (e.content_hash=n.content_hash) FROM knowledge_embeddings_doubao e JOIN knowledge_nodes n ON e.node_id=n.id` = **t**（向量行 hash 始终对齐 node）。
- embed 路由闭环确认：provider.models `["doubao-embedding-vision"]` + KB.embeddingModel `doubao-embedding-vision` + Ark key（`api_key_enc` len=88）+ endpoint `/api/coding/v3/embeddings`，2048 维 halfvec 入库成功。

**冒烟抓出并修复的 2 个 bug：**
1. **V17 `knowledge_nodes` 漏建 `created_by`/`updated_by`**：`KnowledgeNode` 继承 BaseEntity，writer insert 带审计列 → `PSQLException: knowledge_nodes 的 created_by 字段不存在` → 解析 FAILED。**V24** 补两列（`backend/.../V24__knowledge_nodes_audit_columns.sql`）。V17 设计遗漏（knowledge_bases/documents 均有，唯 nodes 漏）。
2. **`KnowledgeEmbeddingMapper.upsert` 引用不存在的 `updated_at`**：V17 §8.3 `knowledge_embeddings_doubao` 设计无 `updated_at`（仅 `created_at`），我初版 ON CONFLICT SET `updated_at=now()` → `PSQLException: updated_at 字段不存在` → job 重试至 DEAD。**已删该行**（重嵌就地覆盖，created_at 保留）。

**坑（运维）：**
- `mvn spring-boot:run` 会拉 `test-compile` → 命中**陈旧测试**（`LlmProviderServiceTest`/`TestConnectionTest` 仍用 `LlmProviderService` 计划11 前的旧 4 参构造）→ 编译失败起不来。重启须加 `-Dmaven.test.skip=true`。这俩陈旧测试留阶段7 一并修。
- Windows curl 经 Git Bash 传中文 body 会 GBK→UTF8 报 `Invalid UTF-8 middle byte`；KB/doc 名走 ASCII。
- admin 登录：`POST /api/auth/login` `{username:admin,password:admin123}` → `data.accessToken`。

---

### 阶段3 已落地（✅ 2026-06-19，BUILD SUCCESS + 冒烟全绿）

范围 = RAG 核心 8 步检索（v6 §4 线性 + §6.1 强制召回 WHERE + §12 不变式），经 `POST /api/knowledge/retrieve` 调试端点暴露。

**新增文件（8）：**
- `knowledge/service/RagConfig.java` — `@Component` §7 预算常量（maxContext6000/modelMax32000/reserve1200→cap6000/maxL0=40/topM=8/topD=5/perDocCap=20/maxL2Read=3/maxRerankPairs=100/abstain=0.5/bm25BoostMax=0.10/chatModel=doubao-seed-2.0-code）+ `computeEffectiveContextCap()`（B1）。
- `knowledge/entity/RagRetrievalLog.java` — `rag_retrieval_logs` 审计流实体（非 BaseEntity，自定义 @Insert）。
- `knowledge/mapper/RagRetrievalLogMapper.java` — trace 写（SQL t1）。
- `knowledge/mapper/RagRetrievalQueryMapper.java` — 全部读 SQL（`<script>` 动态）：dense L0 HNSW `<=>`、L2 children、BM25 `@@`+`ts_rank`、可见集（USER 三级 EXISTS）、I3 hash 复校、L1 metadata、doc_type 枚举、HNSW ef_search。
- `knowledge/service/internal/CitationChecker.java` — A1 确定性正则（剥代码块后扫 `[n]`，越界返 null）。
- `knowledge/dto/RagRetrieveRequest.java` + `RagRetrieveVO.java`（嵌套 RecallHitVO/EvidenceVO/CitationVO/TokenBudgetVO）+ `RagQueryRow.java`（mapper 结果行）。
- `knowledge/service/RagRetrievalService.java` — 编排器，8 步线性 + 内部 record（VisibleSet/FilterScope/RecallHit/L2Candidate/Evidence/EvidencePack）。
- `knowledge/controller/KnowledgeRetrieveController.java` — `POST /api/knowledge/retrieve`，`@RequirePermission("knowledge:read")`。

**Phase1 偏离（DEV-*，已在设计/计划锁）：**
- **DEV-rerank**：无 cross-encoder（Phase2 上 bge-reranker-v2-m3），用**父 L0 cosine sim** 排序 L2 候选 + BM25 boost(≤+0.10)。守 B4（单 query embed）/R1（L2 不嵌）/B3（pair≤100）。abstention 看 best 父L0 sim（非 rerankScore，防 BM25-only 误提权）。
- **DEV-dir-routing**：parser 无 DIRECTORY 节点 → step4 永远降级全库召回（v6 §4 允许），留 hook。
- **DEV-cache-step2**：缓存跳过（rag_answer_cache + Redis 可见集 = 阶段4）。
- **DEV-visible-set**：USER 直接授权（KB/DIRECTORY/DOCUMENT→doc_id）；admin/owner→全库。ROLE/DEPT 聚合阶段4。

**不变式落地：** I1（dense SQL 内 status/deleted/content_hash/embedding_model）/I3（evidence 装载前 node_hash 复校，失配丢弃+记 REINDEX）/P1（post-ANN `document_id⊆visible_set` 在 dense SQL）/A1（Citation 硬校验，越界重生成一次再败 abstain）/A2（best 父L0 sim<0.5 → 固定话术拒答，不写缓存）/B1（cap=min(6000,30800)=6000）/B2（fitToBudget 贪心截断，promptTokens≤cap）/B3（pool 断言≤100）/B4（embed 仅 1 次）/R1（L2 纯 SQL 不嵌）。

**冒烟（smoke-kb kbId=1，admin JWT）：**
- 相关 query「如何安装部署系统」→ `abstained=false`，cand/evidence/citations 各 1，答案带合法 `[1]` 且基于证据，promptTokens=188≤6000，top sim=0.5009。
- 无关 query「量子物理夸克胶子」→ `abstained=true`/LOW_CONFIDENCE/固定话术，top sim=0.17<0.5（A2 正确拒答不编造）。
- trace 入库：`rag_retrieval_logs` 2 行（SUPPORTED/LOW_CONFIDENCE），mode/latency_ms/token_budget JSON 全有。

**观测/调参点（后续）：**
- doubao-embedding-vision 的相似度绝对值偏低（相关 query 仅 ~0.50），阈值 0.5 处于边界 → 若线上过度 abstain，下调 `RagConfig.abstainThreshold`（如 0.35）或按模型校准。这是模型特性，非 bug。
- HNSW `ef_search` 默认 0（未设）；召回不足时 `RagConfig.hnswEfSearch>0` 触发 `SET LOCAL`。
- BM25 'simple' 中文弱（仅 boost，不主导）；Phase2 迁 zhparser/jieba。
- 中文 body 经 Windows curl/shell 会 GBK 报错 → 调试须写 UTF-8 文件 `--data-binary @file`（同阶段2 坑）。

**未验证/留阶段7：** 单测（8 步线性 + 每不变式机械用例）、E2E、权限负例（非授权用户 403）、Citation 越界强制重生成路径、I3 失配丢弃。

---

### 阶段4-A 已落地（✅ 2026-06-19，BUILD SUCCESS + 冒烟全绿）

范围 = Redis 权限可见集缓存（v6 §5.1/§5.2）。USER+ROLE+DEPARTMENT 三层主体并集 → doc_id set，miss 回源 + writeback + per-key 互斥；grant/revoke/doc-delete 后 AFTER_COMMIT 删该 KB 全部缓存 key。answer_cache(step2/B) 推后。

**新增文件（6）：**
- `knowledge/config/VisibilityCacheProperties.java` — `@Component @ConfigurationProperties("rag.visibility-cache")`：enabled/ttl-ms(30min)/scan-count(100)。
- `knowledge/service/internal/VisibleDocSet.java` — 值对象 `{all, docs}`，JSON 载体。
- `knowledge/mapper/VisibilityQueryMapper.java` — `hasKbLevelRead`（KB 级 can_read 探针，3 主体层 OR）+ `computeVisibleDocs3Layer`（DOCUMENT/DIRECTORY/KB × USER/ROLE/DEPT EXISTS 并集，DISTINCT 去重，`<script>` foreach，空 list `<if>` 包裹）。
- `knowledge/service/VisibilitySetService.java` — cache-first：admin→all 不缓存；命中返回；miss→`ConcurrentHashMap` 锁条带 + double-check→DB 三层并集→writeback（Redis 故障降级直算）。
- `knowledge/event/VisibilityInvalidationEvent.java`（POJO `{kbId}`）+ `VisibilityInvalidationListener.java`（`@Async AFTER_COMMIT` → `RedisCallback`+`connection.scan(MATCH vis:*:*:{kb})` + 批量 DEL，Cursor try-with-resources）。

**接线（改 4）：** `RagRetrievalService.step1VisibleSet` 调 `VisibilitySetService.getVisibleDocs`（admin/owner 仍短路 all）；`KnowledgePermissionService.grant/revoke` + `KnowledgeDocumentService.delete` 发 `VisibilityInvalidationEvent(kbId)`；`application.yml` 加 `rag.visibility-cache.*`。

**缓存格式：** key `vis:{tenant}:USER:{userId}:{kbId}`；值 `{"all":true}` 或 `{"all":false,"docs":[...]}`（ObjectMapper，反序列化 readTree+convertValue→List<Long>）；TTL 30min。

**冒烟（注册 vis_test id=3 + 授权 + 登录）：**
- KB 级 read grant（USER/KB/canRead）→ retrieve → 可见集 `{"all":true}`，**写 Redis**；2nd retrieve **缓存命中**（log 无 `computeVisibleDocs3Layer`/`hasKbLevelRead` SQL）。
- revoke → listener AFTER_COMMIT SCAN+DEL → `redis-cli GET vis:1:USER:3:1` = **nil** → 3rd retrieve **403**（无脏缓存）。
- cache miss→compute→writeback→hit→revoke→invalidate→delete 全链路验通。

**已知 gap（follow-up，非 cache 缺陷）：** retrieve 入口 `KnowledgeBaseService.canRead` 只认 **KB 级 USER grant**（hasGrant 过滤 targetType=KB）+ PUBLIC。DOCUMENT/DIRECTORY-only grant 过不了 canRead 门 → 3 层 DISTINCT 路径经端点**不可达**（SQL 编译通过、结构同已验的 EXISTS 探针）。要让 per-doc 粒度生效，需把 visible-set 作**单一权限权威**：canRead 改为「visible-set 非空」（含 doc/dir grant + PUBLIC 展开）。属设计决策，留阶段5 或单独项。

**非目标：** SERVICE_ACCOUNT 主体；目录递归；answer_cache B（step2 保持 no-op）。

**`visibility_event` outbox 列（v6 §5.2/§9.6 原始设计）— 当前 unused，启用有触发条件：**

- **现状**：失效用 Spring 进程内事件（`VisibilityInvalidationEvent`，`@TransactionalEventListener AFTER_COMMIT` + `@Async`），listener `SCAN+DEL` 打**共享 Redis**。多实例正常情况下仍一致——所有实例读同一 Redis，DEL 一次全实例生效。
- **何时启用 outbox（回归 v6 §5.2，满足任一即启用）：**
  1. **多实例部署**（对齐 ADR-001 Phase3：execution 拆独立服务 + Nginx 水平扩展）——需**崩溃恢复 durability**：进程内事件在 `commit` 后、listener `fire` 前实例挂 → 事件丢 → 缓存陈旧至 TTL（30min）。outbox 把失效信号写进同事务 DB 行，独立 worker 扫表兜底，实例重启不丢。
  2. **Redis Cluster**——`conn.scan` 单节点扫，分片模式漏 key（standalone 无此问题）。
  3. **合规要求权限撤销硬实时生效**（不能容忍 30min 陈旧窗口）。
- **落地形态（启用时）**：grant/revoke/doc-delete 同事务 `INSERT knowledge_index_jobs(visibility_event=TRUE)`；独立 worker 定时扫 `visibility_event=TRUE` → DEL Redis → 置 FALSE。复用 §6 outbox 表，不新建。
- 多实例 SCAN 失效合并入本条，不再单列。

---

### answer_cache(B) 已落地（✅ 2026-06-21，`mvn compile` BUILD SUCCESS + 冒烟绿）

范围 = 阶段4-B：跨会话语义答案缓存（v6 §8.9a `rag_answer_cache` 表 V17 已建好，Java 侧补齐）。opt-in 默认关，开 → 重复/近义 query 短路检索。**接进两个检索入口**（用户选 C）：

- `retrieve()`（单KB debug 端点 `/api/knowledge/retrieve`，生成 answer）：step2 命中 → 跳过 step3-8 + 生成，回放缓存 answer；SUPPORTED 后写缓存（answer 列填 JSON{answer,citations,injectedIndexes}）。
- `retrieveEvidence()`（多KB，CHAT/AGENT/WORKFLOW/ask 生产路径，不生成）：step2 命中 → 跳过 step3-7 检索，回放缓存证据 systemPrompt；SUPPORTED 后写缓存（answer 列填 JSON{systemPrompt,citations,injectedIndexes}，answer 字段空）。**不省生成**（调用方按 persona 重新流式生成，正确——答案因人而异；仅检索段 ~3s + 1 次 embed 计费被缓存跳过）。

**新增文件（6）：**
- `knowledge/config/AnswerCacheProperties.java` — `@Component @ConfigurationProperties("rag.answer-cache")`：enabled=false(opt-in)/simThreshold=0.90/topN=5/ttlDays=7。镜像 VisibilityCacheProperties。
- `knowledge/entity/RagAnswerCache.java` — 非 BaseEntity（表无 deleted/version），标量列全映射，**key_embedding halfvec 列不映射**（走自定义 SQL）。镜像 KnowledgeEmbedding。
- `knowledge/dto/CacheCandidateRow.java` — HNSW 近邻检索结果行（id/queryCanonical/cosineDistance/answer/provenanceNodeIds/evidenceHashes/permissionSignature/confidence）。
- `knowledge/dto/CachedPayload.java` — 命中 payload（answer 可空/systemPrompt 可空/citations/injectedIndexes）+ 内嵌 CitationRef{index,documentId,title,nodeId}。
- `knowledge/mapper/RagAnswerCacheMapper.java` — `searchCandidates`（HNSW `<=>` + `WHERE scope_user_id=? AND status='ACTIVE'` per-user 强制）/ `insert`（`::halfvec`，无 ON CONFLICT，重复行靠 decay 清）/ `bumpUsage`。
- `knowledge/service/internal/AnswerCacheService.java` — 核心：`permissionSignature(List<KbScope>)`=HashUtil.sha256(canonical 可见集+kb_scope)；`lookup(qHalf,userId,sig)` HNSW 近邻→sim≥threshold→P3 签名比对→P2a reverifyNode 逐条 hash 复校→bumpUsage+反序列化 answer 列；`store(...)` 组装实体+decay_at+insert（gate+swallow error，缓存写失败不阻断检索）。`KbScope{kbId,allDocs,docIds}` 值对象。

**修改（2 代码 + 1 yml）：**
- `RagRetrievalService.java`：注入 AnswerCacheService + AnswerCacheProperties。
  - retrieve()：step2 插入（qHalf 后、step3 前，命中→trace `CACHE_HIT`+buildVo 回放+return）+ SUPPORTED 后 store（abstain 不写，A2）。
  - retrieveEvidence()：**重构**——per-kb step1（canRead+step1VisibleSet）从循环上提成 `List<KbScopeCtx>`（单次算清，循环复用 vs，行为不变：bm25Fallback/B3/A2/I3 全保留）→ step2 lookup（命中→writeTraceMerged `CACHE_HIT`+回放 EvidenceResult）→ 循环跑 step3/5/6 → SUPPORTED 后 store。
  - 新增 verdict `"CACHE_HIT"`（trace 列自由 String，无 schema 改）+ `KbScopeCtx` record + `toCitationVOs`/`toCitationRefs` 转换。
- `application.yml`：`rag.answer-cache: {enabled:false, sim-threshold:0.90, top-n:5, ttl-days:7}`（镜像 visibility-cache 块）。

**校验链（懒失效，无主动 purge）：**
- **P3 permission_signature**：sha256(per-kb visible 全集 doc-id 或 ALL，按 kbId 排序)。grant/revoke 改可见集 → 签名变 → 旧缓存签名不匹配 → 自动 miss。
- **P2a evidence hash 复校**：命中候选 provenance node 经 `reverifyNode` 取现 content_hash，逐一比对该缓存 evidence_hashes。doc 删（CASCADE 节点删→null）/重传（新 content_hash）→ 失配 → miss。
- **P2b（doc_id⊆visible_set）冗余**：P3 签名对完整可见集做无损 sha256，签名匹配即可见集 byte-identical → 缓存时可见的 evidence doc 必仍可见，P2b 由 P3 蕴含，不单列（省去 node doc_id 查询）。
- **per-user 强制**：searchCandidates SQL 恒带 `scope_user_id=?`，HNSW 跨用户近邻被 WHERE 滤掉，跨用户永不命中。

**复用（零新增读 SQL 除 searchCandidates）：** `HashUtil.sha256`（permission_signature）/ `RagRetrievalQueryMapper.reverifyNode`（P2a）/ `HalfVecUtil`+`LlmGateway.embed`（query embedding，B4 单次复用，命中也不重嵌）/ `VisibilitySetService`（可见集，Redis 缓存）。`VisibilityInvalidationEvent` **不挂钩**（listener 硬绑 `vis:` 命名空间，且 P2/P3 已覆盖其 grant/revoke/delete 触发场景）。

**Phase1 偏离/已知 gap：**
- retrieveEvidence 不省生成（CHAT/AGENT/WORKFLOW 按 persona 重新流式生成）。
- stale 行清理：`decay_at` 写入但无 worker 扫除（留阶段7 ReconciliationJob）。
- store 不查近义已有行直接 insert（重复语义行靠 decay 清，YAGNI）。
- `doc_version_set` 列 unused（P2a hash 已覆盖内容变更）。
- simThreshold=0.90 保守（防假命中返错答案）；doubao abs sim 偏低，近义命中可能偏严，可调。
- 单测（P2 跨用户不命中、doc 编辑逐条 miss、P3 签名变更）统一留阶段7。

**冒烟已验（✅ 2026-06-21，KB1 smoke_doc，admin JWT，yml `enabled:true`）：**
- **同 query CACHE_HIT**：`如何安装部署系统` 首查 SUPPORTED 8121ms（存 answer+citations+injectedIndexes，sig=549b896f…，provenance nodes=[2] hashes=[f017f936…]，usage_count=0，decay_at=7天后）→ 同 query 再查 **CACHE_HIT 190ms**（~43x，0 LLM/embed 计费），usage_count→1→2，trace `CACHE_HIT` 落库 `l2_lexical_fallback=f`。
- **近义 query miss（预期）**：`系统安装部署步骤` 4564ms SUPPORTED（sim<0.90 保守阈，doubao abs sim 偏低故近义难达阈，属预期非 bug；调参点）。
- psql 实测：`rag_answer_cache` 行 `answer` 列 JSON = `{answer, citations:[{index:1,docId:2,title:安装步骤,nodeId:2}], injectedIndexes:[1]}`；permission_signature 64 hex；`scope_user_id` 强制 per-user。

**冒烟抓出并修复的 1 个 bug：**
- **CACHE_HIT trace 写入失败（`l2_lexical_fallback NOT NULL`，同旧 bug #2 同类）**：CACHE_HIT 路径在 step6 前 short-circuit → `trace.l2LexicalFallback` 未设 → null 违 `NOT NULL DEFAULT FALSE` → writeTrace 静默丢（catch 吞错）。retrieveEvidence 多 KB 同病。**修**：两处 CACHE_HIT path 写 trace 前补 `trace.setL2LexicalFallback(false)`。

**环境还原：** yml `enabled:false`（opt-in 默认关）+ backend 重启（cache off）+ `DELETE FROM rag_answer_cache`（2 测试行清）+ 冒烟 3 trace 清。

---

### B1 修复 + 阶段5 已落地（✅ 2026-06-20，`mvn compile` BUILD SUCCESS，冒烟待跑）

范围 = RAG 接进 Chat/Agent/Workflow 三模式 + 修 canRead 权限 gap（B1）。

**B1 修复（canRead 权限单一权威）：** `KnowledgeBaseService.canRead` 加 cheap prefix `canManage||PUBLIC`，再委托 `VisibilitySetService.getVisibleDocs`（`isAll()||!docsOrEmpty().isEmpty()`）。DIRECTORY/DOCUMENT/ROLE/DEPT 授权现可达。`canWrite` 保持 KB 级 USER-only（policy，非缺陷）。

**新增文件（10）：**
- `db/migration/V25__kb_bindings_chat_kbids_retrieval_node.sql` — chat_sessions.kb_ids BIGINT[] + agent_kb_bindings + workflow_kb_bindings（mirror V16）+ RETRIEVAL 节点类型。
- `common/typehandler/LongArrayTypeHandler.java` — PG `BIGINT[] ↔ List<Long>`。
- `agent/entity/AgentKbBinding.java` + `agent/mapper/AgentKbBindingMapper.java` + `agent/service/AgentKbBindingService.java` + `agent/dto/AgentKbBindingVO.java`。
- `workflow/entity/WorkflowKbBinding.java` + mapper + `workflow/service/WorkflowKbBindingService.java` + `workflow/dto/WorkflowKbBindingVO.java`。
- `knowledge/service/RagScopeResolver.java` — P4 求交（mode 派发 scope + canRead 过滤 + 同 embedding_model 约束）+ `resolveNodeKbs`（检索节点回调，无 workflowId）。
- `knowledge/dto/EvidenceResult.java` — retrieveEvidence 返回（systemPrompt + injectedIndexes + citations + abstained）。
- `knowledge/dto/AskRequest.java` + `knowledge/controller/KnowledgeAskController.java` — `POST /api/knowledge/ask` SSE（retrieveEvidence → chatStream → CITATION → DONE）。

**修改：**
- `KnowledgeBaseService`（canRead B1，:136）+ 注入 VisibilitySetService。
- `RagRetrievalService`（抽 `retrieveEvidence(List<Long> kbIds,...)` 多 KB：per-kb steps1/3/5/6(gather) → 合并 L0+L2 → 全局 maxBm rerank → A2 全局 best → 单 writeTrace merged kb_ids；`gatherL2Candidates`+`rerankWithBoost` 拆分；`evidenceBlock`；保留单 kb `retrieve(req,userId)` back-compat）。
- `chat/entity/ChatSession.java`（kbIds BIGINT[] + autoResultMap）+ `chat/dto/ChatRequest.java`（kbIds）。
- `chat/service/ChatSessionService.java`（CHAT 注入：`resolveRagForChat` 仅 CHAT 模式 → retrieveEvidence → abstain 短路 / 证据 SYSTEM msg + post-gen citation disclaimer，blocking + stream；`isAdmin` helper）。
- `chat/dto/StreamEvent.java`（+CITATION 类型/factory）。
- `engine/strategy/AgentRoutingStrategy.java`（AGENT scope → retrieveEvidence → `firstStepConfigOverride` 注 step1 systemPrompt）。
- `engine/executor/SkillExecutor.java`（resolveStepConfig 对 systemPrompt PREPEND，保留原 Agent 人设）。
- `runtime/dto/RuntimeNodeType.java`（+RETRIEVAL）+ `runtime/service/WorkflowDefinitionAssembler.java`（RETRIEVAL 需 kbId/kbIds 校验）+ `runtime/service/RuntimeNodeCallbackService.java`（+RETRIEVAL 分支：nodeConfig kbIds ∩ 用户 → retrieveEvidence → output.text 证据供下游）。
- `agent/controller/AgentController.java` + `workflow/controller/WorkflowController.java`（kb-bindings GET/PUT 端点）。
- `runtime-sidecar/app/node_runtime.py`（resolve_source +RETRIEVAL）+ `runtime_executor.py`（回调触发集 +RETRIEVAL）。
- `frontend/src/types/workflow.ts`（+'retrieval' 类型 + NodeData kbId/kbIds/query）+ `utils/workflowMapper.ts`（FLOW_TO_BACKEND_TYPE + CONFIG_KEYS +retrieval/kbId/kbIds/query）。

**决策落实：** Agent/Workflow kbIds = 连表（非 config key）；WORKFLOW RAG = 检索节点回调（v6 §2.4，非 context 注入——chat-stream `streamWorkflow` 绕 engine）；多 KB 合并点 = L0 层 + 同模型约束 + 全局 maxBm；chat citation 失效 = append disclaimer（不 regenerate，上下文富）；retrieveEvidence 不含生成（快，~3s，同步注入同 memory）。

**Phase1 偏离/已知 gap（非 bug，记后续）：**
- AGENT/WORKFLOW 仍非真流式（吃 interface default / streamWorkflow），仅 CHAT 真流式。
- WORKFLOW per-node kbIds 未做（仅 workflow 级 binding + RETRIEVAL 节点自身 config）。
- `/api/runtime/callbacks/**` permitAll（检索节点继承信任模型，留 HMAC 后续）。
- 前端检索节点 UI（ComponentPalette/FlowCanvas/PropertyPanel）未做（类型/映射 round-trip 已通；冒烟可经 API 建 RETRIEVAL 节点）。
- chat `metadata` citations 未填（inline `[n]` + disclaimer 已够；EvidenceResult.citations 已备 /ask CITATION）。
- `@Transactional` 跨 LLM 调用（pre-existing，RAG 加剧，出范围）。

**冒烟待跑（7 条，需起 backend + Ark key + sidecar）：** B1（DOCUMENT-only grant 不再 403）/ CHAT（绑 kb_ids 带引用 + 无关 abstain + 无权限不召回）/ AGENT（agent_kb_bindings → 首步答案含证据）/ WORKFLOW（RETRIEVAL 节点 → sidecar 回调 sourceType=RETRIEVAL → 下游拿证据）/ /ask SSE（CHUNK…CITATION…DONE）/ 多 KB（同模型合并 + 混合模型限定）/ P4 负例（无权限绑定 KB → 空集 abstain）。

---

### 记忆模式开关 已落地（✅ 2026-06-20，`mvn compile` + `vue-tsc` 通过，冒烟待跑）

范围 = opt-in「记忆模式」开关：关=纯裸聊天/Agent/工作流（零外部上下文）；开=RAG 证据 + 用户长期记忆（+预留 answer_cache）。4 层优先级 session > agent/workflow > global，默认关。

**优先级解析 `RagModeResolver`（新 `knowledge/service/RagModeResolver.java`）：**
```
resolve(mode, sessionRagEnabled, agentId, workflowId):
  if sessionRagEnabled != null → session 最高
  AGENT: agent.config.ragEnabled（非 null）
  WORKFLOW: workflow.rag_enabled（非 null）
  → global (system_settings rag.memory.enabled, 默认 false)
resolveForWorkflowCallback(executionId): 经 executionLog 取 workflowId → resolve(WORKFLOW)（回调无 session）
```

**存储：**
- 全局：`system_settings` 行 `rag.memory.enabled`（V26 seed，默认 false）。
- 会话：`chat_sessions.rag_enabled`（V26，null=继承）。
- Agent：`Agent.config` JSONB 键 `ragEnabled`（无迁移，三态 null/true/false）。
- Workflow：`workflows.rag_enabled`（V26，null=继承）。

**门控：**
- `ChatSessionService`（CHAT 证据 + 记忆）：sendMessage/sendMessageStream 算 `ragOn` → `context.setRagEnabled(ragOn)`；false 跳过 `resolveRagForChat`（RAG）+ `buildMemoryContext`（记忆注入）+ 全部 6 处 `extractMemoriesAsync`（记忆抽取）。
- `AgentRoutingStrategy`：读 `context.isRagEnabled()`，false 跳过 `resolveAgentEvidence`。
- `RuntimeNodeCallbackService` RETRIEVAL：`resolveForWorkflowCallback`，false → "记忆模式未开启" no-op。
- 会话持久化：`ChatRequest.ragEnabled` 非 null → 写 `session.rag_enabled`（前端开关随消息落库）。

**新建：**
- `db/migration/V26__rag_memory_toggle.sql`、`knowledge/service/RagModeResolver.java`、`system/dto/RagMemorySettingsVO.java` + `RagMemorySettingsUpdateRequest.java`、`frontend/src/components/settings/RagMemorySettingsTab.vue`。

**改：**
- `SystemSettingService`（+getBoolean/setBoolean + getRagMemoryEnabled/updateRagMemoryEnabled）+ `SystemSettingController`（GET/PUT `/api/system/settings/rag-memory`，role:manage）。
- `ChatSession`/`Workflow`（ragEnabled）+ `ExecutionContext`（ragEnabled 字段）+ `ChatRequest`（ragEnabled）。
- `ChatSessionService`（解析+门控+持久化）、`AgentRoutingStrategy`、`RuntimeNodeCallbackService`、`AgentKbBindingService.setRagEnabled`、`WorkflowKbBindingService.setRagEnabled`、`AgentController`+`WorkflowController`（PUT rag-enabled 端点）。
- 前端：`api/system.ts`（ragMemory API）+ `api/chat.ts`（ChatSendRequest/payload ragEnabled）+ `stores/chat.ts`（sendStreamingMessage/sendMessage 透传）+ `views/SettingsView.vue`（RAG/记忆 tab）+ `views/ChatView.vue`（会话 NSwitch + ref）。

**已知 gap（文档化）：**
- **记忆冲突无处理（实证 2026-06-20，M6 补测）**：发「女儿小红5岁」→（延迟/稀疏可能漏抽）→ 再「更正为儿子小明3岁」终态：`child_1_name=小红`（女儿名，**未覆盖**）+ `子女概况=儿子小明`（新 key）+ `child_1_age 5岁→3岁`（同 key 静默覆盖）+ `has_daughter=小明`（key/value 语义错配垃圾）+ `child_1_gender=男`。即**新旧矛盾共存**（女儿+儿子同时在库）+ key 语义错配。根因：① LLM 自由命名 key 不稳定（child_1_name/has_daughter/子女概况/child_1_gender 指同一概念却不命中 upsert）→ 不覆盖；② 偶然命中 key（child_1_age）静默覆盖无历史；③ 无冲突检测/无版本/无人工确认。`buildMemoryContext` 注入全量（conf≥0.5，皆 1.0）→ 下游 LLM 收到矛盾上下文。**修向（未做）**：固定 schema key 集 + key 归一化；冲突检测（新值 vs 旧值语义矛盾→双版本+confidence 衰减或 pending 人工确认）；updated 版本历史不丢旧值。
- WORKFLOW 检索节点回调无 session → 仅 workflow + global（session 覆盖不进工作流执行）。
- 默认关改变现状（绑 KB 不再自动跑 RAG）——需用户主动开。
- Agent/Workflow detail 页 ragEnabled UI 未做（端点已就绪，可后续加 NSwitch）。
- answer_cache(B) 未做，开关预留 gate。

**冒烟待跑（6 条）：** 全局关+全空→裸聊 / 全局关+会话开→带RAG+记忆 / 全局开+会话关→裸聊（覆盖）/ AGENT config ragEnabled 开→带证据 / WORKFLOW retrieval+workflow.rag_enabled 开→回调出证据；关→no-op / 记忆抽取：关→user_memories 无新行。

---

### 冒烟 + 3 bug 修复 + 记忆/trace 端点 已落地（✅ 2026-06-20，BUILD SUCCESS + 冒烟绿）

起 backend（:8080）+ runtime-sidecar（:8090）+ frontend（:5173）实跑。Flyway V25+V26 落库（schema v26），Ark key 已录（provider id=5 `doubao-embedding`，`test-embed` 2048 维 781ms 通过），KB id=1 `smoke-kb` 1 篇 INDEXED 文档 + 2 SECTION 节点 + 1 向量行（dim 2048）+ jobs 全 DONE。

**冒烟结果（PASS）：**
- retrieve：RELATED 带 `[1]` 生成答案 / UNRELATED abstain LOW_CONFIDENCE（A2 正确拒）/ P4-neg kbId=99999 → 404。
- /ask SSE：THINKING+CHUNK+CITATION（`{index:1,documentId:2,title:安装步骤,nodeId:2}`）+DONE，CITATION 在 DONE 前。
- CHAT（M1/M2/M3）：global OFF→`rag_retrieval_logs` delta=0（RAG 跳过）/ global ON→delta=+1（verdict SUPPORTED kb=[1]，证据注入）/ session `ragEnabled=false` 覆盖→delta=0。oracle = trace 行数 delta。
- AGENT（M4）：`agent_kb_bindings`→[1] + Agent.config ragEnabled=true → AGENT session 发消息 delta=+1（SUPPORTED）。

**冒烟抓出并修复的 3 个 bug：**
1. **CHAT kbIds 不落库（阶段5 CHAT RAG 不可达）**：`ChatSessionService` 全链路从不 `setKbIds`——createSession 存 ragEnabled 漏 kbIds，sendMessage/sendMessageStream 只持久化 ragEnabled 不存 kbIds → `resolveRagForChat` 读 `session.getKbIds()` 恒 null → `resolveEffectiveKbs` 空集 → `RagInjection.none()` → CHAT RAG 永不触发。**修**：3 处（createSession + sendMessage + sendMessageStream）镜像 ragEnabled 模式加 `setKbIds` 持久化。
2. **retrieveEvidence trace 写入失败（`l2_lexical_fallback NOT NULL`，/ask 同病）**：多 KB 路径 `writeTraceMerged` 从不 `setL2LexicalFallback`，实体 Boolean 字段 null → INSERT 显式传 null 违反 `NOT NULL DEFAULT FALSE`（DEFAULT 仅列缺省生效）→ trace 静默丢（`writeTraceMerged` catch 吞错「不影响结果」）。单 KB `retrieve()` line 153 有设，多 KB 漏。**修**：retrieveEvidence per-kb 循环后、首次 `writeTraceMerged` 前加 `trace.setL2LexicalFallback(bm25Fallback[0])`（任一 KB 触发即 true）。
3. **Agent.config jsonb 写失败（setRagEnabled 500）**：`Agent.config` 纯 String 无 typehandler → MyBatis `updateById` 发 varchar 进 jsonb 列被 PG 拒（`字段 config 类型 jsonb 但表达式 varchar`）→ `setRagEnabled` 端点 500。RAG 注入本身（AgentRoutingStrategy）正确，仅 toggle 端点坏。**修**：`Agent` 实体 `@TableName(value="agents", autoResultMap=true)` + `@TableField(typeHandler=JsonbStringTypeHandler.class)` config（复用既有 `common.typehandler.JsonbStringTypeHandler`，同 `Skill.config` 模式）。验：`{enabled:true}`→200+DB `{"ragEnabled":true}`；`{enabled:null}`→删 key→`{}`；AGENT M4 经端点（非 SQL 绕过）delta=+1。

**新增端点（2 组，7 文件）：** 对应「记忆无 controller / trace 无查询清理」两 gap。
- **`/api/chat/memories`**（用户自服务，按 current userId 隔离，无需权限——记忆是用户私有资产）：`GET` 列表（updatedAt 倒序）/ `DELETE /{id}` 删单条（ownership 校验，非本人 false）/ `DELETE` 清空自己全部。新建 `UserMemoryVO` + `MemoryService.listMemories/deleteMemory/clearMemories` + `MemoryController`。
- **`/api/knowledge/retrieval-logs`**（审计，`knowledge:manage`）：`GET` 分页（filter userId/mode/时间范围走 SQL 强类型列；**kbId 走 Java post-filter**——`kb_ids` 存 text `"[1,2]"`，SQL LIKE 误匹配 1↔10，解析后精确判定，仅作用于当前页）/ `DELETE /{id}` / `DELETE ?before=ISO-8601`（按时间批量清理）。新建 `RagRetrievalLogVO` + `RagRetrievalLogService` + `RagRetrievalLogController`；`RagRetrievalLogMapper` 改 `extends BaseMapper`（保留 `@Insert`，自定义 insert 重命名 `insertTrace` 避与 BaseMapper `insert(T)int` 返回类型冲突）+ 2 处 `logMapper.insert→insertTrace`。

**记忆机制确认（答用户问）：** LLM 抽取后记忆（非原文）。`extractMemoriesAsync(userId, userMessage, assistantResponse)` 取用户消息+助手回复 → LLM（`doubao-seed-2.0-code` temp 0.3 maxTokens 500）出 JSON `[{category(PREFERENCE/FACT/FEEDBACK),key,value,confidence}]` → regex 抽 → `upsertMemory` 按 `(userId,key)` 覆盖（非追加）→ 存 category/key/value/source=INFERRED/confidence。注入 `buildMemoryContext` 仅取 `confidence≥0.5`。**已知弱点**：parse 用 regex 非 Jackson（代码自承 production 用 Jackson）；source 恒 INFERRED 无显式录入入口；同 key 直接覆盖无冲突合并。

**时间信息（答用户问）：** 全表有时间列——KB/文档/节点 BaseEntity 全套（created_at/by+updated_at/by）；index_jobs/user_memories created+updated；embeddings 仅 created_at（重嵌就地覆盖）；rag_retrieval_logs 仅 created_at（trace 不可变）。

**未跑（留后续）：** M5 WORKFLOW（5 workflows 全 DRAFT 无 RETRIEVAL 节点 + 0 workflow_kb_bindings → 需造 RETRIEVAL workflow def + sidecar 回调往返，重型）/ M6 记忆抽取实测（ragOn→extractMemoriesAsync→GET memories 见新行）/ B1 DOCUMENT-only grant 负例（admin 无法做真负例，需无权限用户）/ 多 KB 合并（仅 1 KB）/ P4 无权限绑定 KB 负例（需无权限用户）。

**环境还原：** global toggle=false（默认）/ agent4 config=`{}`（清 ragEnabled）/ agent_kb_bindings agent4→[1] 保留（无害）/ trace 已被冒烟 DELETE?before 清空（测试数据，可重生成）。

---

## 四、后续阶段（未开始）

| 阶段 | 内容 | 状态 |
|------|------|------|
| 3 | `RagRetrievalService` 完整 8 步（核心）+ 不变式 | ✅ 完成（2026-06-19，冒烟全绿） |
| 4 | 权限可见集（Redis，USER+ROLE+DEPT 三层并集）+ answer_cache（per-user） | ✅ 可见集（4-A，2026-06-19）+ answer_cache(B)（2026-06-21，BUILD SUCCESS + 冒烟绿） |
| 5 | Chat/Agent/Workflow 集成（KB 绑定 scope，P4 求交） | ✅ 完成（2026-06-20，BUILD SUCCESS）；冒烟 retrieve/ask/CHAT/AGENT 绿 + 3 bug 已修；WORKFLOW(M5) 待跑 |
| 6 | 前端 `/knowledge` 页 + 检索调试面板 | 未开始（后端 `/retrieval-logs` + `/memories` 端点已就绪可对接） |
| 7 | 一致性对账 + 失效链路 + **全部单测/集成测收尾** + Phase1 验收 + E2E | 未开始 |

---

## 五、测试策略（统一留最后）

**开发期不写测试**，以 mvn compile + DB 实测（psql 验表/数据/索引）+ 必要时冒烟（起 backend 打 API）为准。全部测试集中在**阶段7**：

- 阶段1：建库无 `knowledge:write` 被拒、grant 无 manage 被拒、BaseEntity 自动填充、owner||admin 鉴权矩阵。
- 阶段2：embed 维度=2048、I2 re-check 作废、I4 幂等不重复写向量、并发更新只新版本 job 接管。
- 阶段3：8 步线性无循环、不变式机械校验（I1/I2/I3/I4/P1/B1-B4/A1/A2/R1）。
- 阶段4：P2 跨用户不命中、文档编辑 evidence hash 逐条 miss、ROLE/DEPT 聚合解析。
- 阶段5：P4 三身份（Chat/Agent/Workflow）求交 + 任一空集。
- 阶段7：v6 §10.2 九条验收 + 浏览器级 E2E。

理由：RAG 链路强耦合（检索/缓存/权限互依），分阶段写测试会被后续阶段重构推翻；收尾时整体稳定后一次写全，避免返工。

---

## 六、待办（非阶段任务）

- [ ] **V12–V26 + 本次改动提交 git**（当前全 untracked；含 3 bug 修复 + `/api/chat/memories` + `/api/knowledge/retrieval-logs` + DTO/VO）。部署前必须。
- [x] **M5 WORKFLOW 冒烟（✅ 2026-06-21 PASS）**：START→RETRIEVAL→END workflow + bind KB1 + rag_enabled → run → RETRIEVAL 回调 retrieveEvidence 返证据 `[1]` → EXECUTION_COMPLETED。修 sidecar callback response null→[] 校验。
- [x] **M6 记忆抽取实测（✅ 2026-06-20 PASS）**：global ON + CHAT msg → 4 行新记忆（name/age/occupation/favorite_language，conf 1.0 INFERRED）；会话 `ragEnabled=false` 负例→0 新行（门控正确）。环境已还原。
- [ ] 部署目标 WinServer 2019 前置条件已文档化：`项目工程文档/WinServer2019部署前置条件.md`。
