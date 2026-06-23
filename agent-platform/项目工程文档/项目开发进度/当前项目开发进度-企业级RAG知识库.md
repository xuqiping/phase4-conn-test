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
- ✅ **前端检索节点 UI 完成（2026-06-22，vue-tsc EXIT=0 + 浏览器冒烟全绿）**：工作流编辑器 RETRIEVAL 节点前端闭环（阶段5 follow-up gap 收口）。详见下「前端检索节点 UI 已落地」。
- ✅ **记忆冲突 judge 准确率调优 完成（2026-06-22，`mvn compile` BUILD SUCCESS + 冒烟全绿）**：regex→Jackson（杀 count-mismatch 静默漏判）+ temp 0.3→0.0 + prompt key 归一/few-shot + route 结构化（弃 string-contains）。详见 `项目开发进度/当前项目开发进度-个人记忆知识库（含冲突解决）.md` 末尾「judge 准确率调优 已落地」。
- ✅ **运行时冒烟 收口（2026-06-22，judge + 前端检索节点 UI 双项全绿）**：① judge 调优冒烟 — 场景1 KEEP_NEW（Java→Python 更正→PENDING+askText→「保留Python」→RESOLVED，Python 留 Java 删）+ 场景2 FLAGGED（无关「天气」→两条同 block 共存打 FLAGGED）全 PASS，原非确定性消除。② 前端检索节点 UI 冒烟（playwright-mcp 驱动）— 拖拽渲染 + 属性面板 KB 多选/查询 + save round-trip（type=RETRIEVAL + config kbIds/query 持久化）+ 连边 onConnect + 运行 execution SUCCESS，RETRIEVAL 回调返证据「[1] 安装步骤 PostgreSQL16/pgvector/Flyway/Redis/SpringBoot8080」。**冒烟期误判 2「瑕疵」，深查后纠正根因 + 已修（见下「运行时冒烟 已落地」+「2 根因修复 已落地」）**：① 初判「save 清空 ragEnabled」实为「WorkflowVO/DetailVO 不暴露 ragEnabled」+ 测试 curl 用错 key（`ragEnabled` vs 控制器要的 `enabled`，DB 实际持久）② `listFlagged` VO 新候选 `id=null`（真 bug，snapshot 重建丢 id）。**两均修 + 复验 PASS**（detail/list 返 ragEnabled；FLAGGED 两 candidate id 非空）。环境已还原（toggle OFF / memories+conflicts+retrieval_logs=0 / 测试 workflow 删 / sidecar 停）。详见下「运行时冒烟 已落地」+「2 根因修复 已落地」。
- ✅ **answer_cache 生产路径冒烟 完成（2026-06-22，PASS）**：retrieveEvidence 多KB路径（CHAT+AGENT）缓存全链路验通。CHAT：绑 KB1 同 query 二查 trace `CACHE_HIT` 167ms（检索段短路，~3-8s→167ms），cache 行 `answer` 列=`{systemPrompt,citations,injectedIndexes}` JSON（非最终答案，证不省生成），usage_count 0→1，scope_user_id 强制 per-user。AGENT：agent_kb_bindings→[1] + Agent.config ragEnabled=true，2 次 AGENT 调用均 cross-mode 命中 CHAT 存的 cache 行（cache 按 user+query+permission_signature 索引而非 mode，CHAT/AGENT 共享是设计特性），trace 2 新行 CACHE_HIT（254/198ms），usage_count 1→3。2nd AGENT 答案带 agent4 persona → 证按 persona 重新生成。环境已还原（yml enabled=false + agent4 config={} + cache/trace/sessions 清 + backend/sidecar 停）。**已知**：近义 query 仍 miss（doubao abs sim 偏低 < 0.90 保守阈，line 283 已记，非 bug）；stale 行清理 worker 仍留阶段7。
- ✅ **阶段6 前端知识库页 MVP 完成（2026-06-22，`vue-tsc` EXIT 0 + 浏览器冒烟全绿）**：单 `/knowledge` 路由 + n-tabs 两 tab。Tab「知识库管理」：KB 表格（全列）+ 行操作（文档/编辑/授权/删除，按 `knowledge:write` + VO `canManage` 门控）+ KbFormModal（新建/编辑，name/visibility/summaryStrategy/embeddingModel/rerankModel）+ KbPermissionModal（USER/ROLE/DEPARTMENT 授权 + 即时 grant/revoke）+ 文档抽屉 DocumentManager（n-upload-dragger `:custom-request` FormData 上传，新 pattern + 3s 状态轮询 PENDING→…→INDEXED/FAILED 停 + unmount 清 timer）。Tab「检索调试」：RetrievalDebugPanel（kbId/maxL0/docTypes + query → `POST /retrieve` → abstained 徽标/answer/引用表/候选L0表/证据L2表/token预算/traceId+latency）。新增 7 文件（KnowledgeView + stores/knowledge + 4 组件 + 扩展 api/knowledge.ts）+ 改 router/Sidebar。**冒烟**：playwright-mcp 登录→/knowledge→KB 表显 smoke-kb（canManage 显授权按钮）→检索调试 query「如何安装部署系统」→ SUPPORTED 带 `[1]` 答案 + 引用 doc2「安装步骤」+ 候选L0 cosSim 0.5010 + 证据L2 + token(prompt188/cap6000) + trace 6789ms；文档抽屉显 smoke_doc.md「已索引」+ 拖拽区。**Defer**：RAG 问答 SSE 区（`/ask` CITATION consumer）、检索审计表（retrieval-logs，knowledge:manage）、记忆/冲突列表、目录树（**后端无端点**需建 KnowledgeNodeController）。
- ✅ **下一步（已全部完成 2026-06-23）**：阶段6 后续（RAG问答区 SSE #1 + 检索审计表 UI #2 + 记忆/冲突列表 UI #3）/ 目录树 `KnowledgeNodeController` 端点 #4 / ReconciliationJob autoRepair 扩 `claimBatch` 支持 REINDEX #7 / rag_memory_facts decay #8 — 全部收口，见下「八、必做收口」。后端 `mvn test` 294 绿 / 前端 vue-tsc EXIT 0。
- ✅ **必做收口 #8 完成（2026-06-23，`mvn test` 17 测绿）**：`rag_memory_facts` decay 兜底（sibling purge，对齐 answer_cache）。建 `RagMemoryFact` 实体（非 BaseEntity，halfvec 不映射）+ `RagMemoryFactMapper`（`deleteDecayed`/`countDecayed`，镜像 RagAnswerCacheMapper）+ `ReconciliationTxService.purgeDecayedMemoryFacts`（批次循环）+ `ReconciliationWorker.poll()` 全局清（接 answer_cache purge 后）+ TxService 构造器 5→6 参 + 3 新测。M2 软提示表当前无生产者→调用通常返 0，接口就位供将来启用无需再补对账。详见下「八、必做收口」。
- ✅ **必做收口 #7 完成（2026-06-23，`mvn test` 43 测绿）**：ReconciliationJob autoRepair 闭环（drift→REINDEX 修复链路打通）。`claimBatch` 扩认领 UPSERT+REINDEX（`.in(jobType,...)` 替 `.eq`，REINDEX 处理同 UPSERT：重嵌 node.content+upsert）+ `enqueueReindexJobs` 真实现（读 node 当前 content_hash，建 job，ON CONFLICT(idempotency_key) DO NOTHING 幂等，不再 seam）+ 新 `repairDrift(kbId)` 入口（findDriftedNodeIds→enqueue）+ worker scanBatch `autoRepair&&drift>0`→repairDrift。autoRepair 默认仍 false（opt-in，启用担 re-embed 计费，与 enabled 同哲学）。详见下「八、必做收口」。
- ✅ **必做收口 #4 完成（2026-06-23，`mvn test` 3 测绿）**：目录树后端端点 `GET /api/knowledge/documents/{docId}/nodes`（`knowledge:read` + canRead 门）。flat 节点列表（id/parentId/level/nodeType/title/tokenCount/status），前端按 parentId 建树渲染文档大纲（L0 摘要 + L2 原文子节点）。不暴露 content（L2 原文可能大）+ contentHash（内部用）。deleted 由 @TableLogic 自动滤。详见下「八、必做收口」。
- ✅ **必做收口 #5 + #6 完成（2026-06-23，vue-tsc EXIT 0）**：前端 workflow 级 + Agent detail 记忆模式 toggle。workflow 编辑器 topbar + Agent 详情 hero 各加 NSwitch「记忆模式」（镜像 ChatView session toggle 范式：size="small" + tooltip），调专用 `PUT /{id}/rag-enabled {"enabled":...}` 端点（乐观更新+失败回滚）。workflow 类型加 `ragEnabled?`（VO 已暴露）；Agent 从 config JSONB 解析 ragEnabled（VO 无扁平字段）。详见下「八、必做收口」。
- ✅ **必做收口 #2 完成（2026-06-23，vue-tsc EXIT 0）**：检索审计表 UI。`/knowledge` 加「检索审计」tab（gate `knowledge:manage`），`RetrievalAuditPanel` 组件：分页表（id/时间/用户/模式/verdict 标签/查询/延迟/KB）+ 过滤（userId/kbId/mode/时间范围）+ 行删 + 按时间批量清（默认 7 天前）+ 详情抽屉（trace/verdict/延迟/token预算/候选L0/证据L2 JSON pretty）。api/knowledge.ts 加 `RagRetrievalLog` 类型 + 3 法（page/delete/deleteBefore）。详见下「八、必做收口」。
- ✅ **必做收口 #3 完成（2026-06-23，vue-tsc EXIT 0）**：记忆/冲突列表 UI。ChatView 加「记忆」按钮 + 抽屉挂 `MemoryManagerPanel`（两区：我的记忆表=查/删/清空 + 记忆冲突=FLAGGED 分组候选 + KEEP_NEW/OLD/BOTH/DISCARD 解决）。api/chat.ts 加 `UserMemory`/`MemoryCandidate`/`MemoryConflict` 类型 + 5 法（list/delete/clear/listConflicts/resolve）。记忆按 current userId 隔离自服务，无需权限。详见下「八、必做收口」。
- ✅ **必做收口 #1 完成（2026-06-23，vue-tsc EXIT 0）**：RAG 流式问答 SSE。`/knowledge` 加「RAG 问答」tab 挂 `RagAskPanel`：KB 多选 + query → `askStream` 异步生成器消费 SSE（CHUNK 追加答案 / CITATION 解析引用列表 / ERROR/DONE 收尾 / 停止=abort）。api/knowledge.ts 加 `askStream(query,kbIds,signal)` + SSE 解析（镜像 workflow runStream + chat store CHUNK 范式）。引用列表 [n] 标注 doc/node。**🎉 8 项必做收口全部完成**。
- ✅ **8 项必做收口 全部完成（2026-06-23）**：#8 rag_memory_facts decay / #7 autoRepair REINDEX / #4 目录树端点 / #5 workflow toggle / #6 Agent/Workflow toggle / #2 检索审计表 / #3 记忆冲突列表 / #1 RAG 问答 SSE。后端 `mvn test` **294 测 0 错**（281→294，+13 新测，零回归）+ 前端 `vue-tsc --noEmit` **EXIT 0**。详见下「八、必做收口」各节 + §四「阶段6」表已无 Defer。
- 📌 偏好：所有产出文件写项目内目录（不写 `~/.claude`），见 memory `feedback-files-in-repo`。
- ✅ git：V12–V28 + answer_cache 全套已提交并推送 origin/main（2026-06-22，5 主题 commit + merge origin/main 的 file-keeper 工作，详见下「六、待办」）。

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

### 前端检索节点 UI 已落地（✅ 2026-06-22，`vue-tsc --noEmit` EXIT=0，冒烟待跑）

范围 = 阶段5 follow-up gap「前端检索节点 UI 未做」收口。工作流编辑器可视化建 RETRIEVAL 节点闭环（前：仅能经 API 建）。

**新增文件（2）：**
- `frontend/src/api/knowledge.ts` — KB 列表 API（`GET /knowledge/bases`，复用 request 实例）+ `KnowledgeBase` 类型（对应后端 `KnowledgeBaseVO` 子集：id/name/visibility/embeddingModel/canRead/canManage 等）。
- `frontend/src/components/workflow/RetrievalNode.vue` — 画布节点组件（镜像 `AgentRefNode`）：顶部 Handle + 紫色(#8b5cf6) 检索图标 header + body 显 label + meta（`KB {ids} · 已设查询/无查询`）+ 底部 Handle。

**修改（4）：**
- `ComponentPalette.vue`：新「知识检索」section（位于「流程控制」与「输入组件」间），拖拽项 `onDragStart('retrieval', '知识检索', { sourceType: 'RETRIEVAL' })`；import `DocumentTextOutline` + 加 `.palette-item__icon--retrieval` 紫色样式。
- `FlowCanvas.vue`：`nodeTypes` 注册 `retrieval: markRaw(RetrievalNode)` + import；`onDrop` data 透传 `kbId/kbIds/query`（round-trip 配套）。
- `PropertyPanel.vue`：新 `v-if="type==='retrieval'"` section — KB 多选 `n-select multiple`（绑 `kbIds`，回退显 `kbId` 单值）+ 查询 `n-input textarea`（占位提示支持 `{{上游别名.输出变量}}` 模板）+ 说明 notice（运行时 KB∩用户可见集 + 需开记忆/RAG 模式）；`typeMap`+`iconMap`（SearchOutline）+`supportsAlias` 加 `retrieval`；加 `knowledgeBases` ref + `knowledgeBaseOptions` computed + `loadReferenceOptions` 并发拉 KB（失败降级空，不阻断 Agent/Workflow 选项）；`.property-panel__type-icon--retrieval` 紫色样式。emit `update-node-data` + `updateNodeData` value 类型 widen 加 `number[]`（kbIds 多选）。
- `WorkflowEditorView.vue`：`onUpdateNodeData` value 类型同步 widen 加 `number[]`。

**决策：** UI 用 `kbIds`（多选，对齐后端 `RuntimeNodeCallbackService` RETRIEVAL 读 nodeConfig `kbIds` ∩ 用户）；`kbId`（types 已有）保留作单值回退显示，不单独 UI。query 暂静态 textarea（支持模板占位提示，实际模板解析留后续）。

**运行时冒烟（✅ 2026-06-22，playwright-mcp 全绿）：** 起 backend + sidecar + frontend，造 workflow id=11（START→RETRIEVAL→END）+ rag_enabled=true。从「知识检索」面板项拖拽到画布 → RetrievalNode 渲染（meta 随配置响应：未绑定→`KB 1 · 已设查询`）→ 属性面板 KB 多选选 smoke-kb + 查询填「如何安装部署系统」→ 鼠标拖 handle 连边 START→RETRIEVAL→END（onConnect）→ 保存 → 后端 def 持久化：node type=`RETRIEVAL` + config=`{"kbIds":[1],"query":"如何安装部署系统","nodeAlias":...}` + 2 edges（mapper `retrieval`↔`RETRIEVAL` + CONFIG_KEYS 全通）→ 运行 execution id=63 SUCCESS，RETRIEVAL NODE_COMPLETED 证据「[1] 安装步骤 PostgreSQL16/pgvector/Flyway/Redis/SpringBoot8080」+ EXECUTION_COMPLETED。

---

### 运行时冒烟 已落地（✅ 2026-06-22，judge + 前端检索节点 UI 双项全绿）

范围 = 收口 §〇「judge 调优冒烟待跑」+「前端检索节点 UI 冒烟待跑」两项运行时验证。

**A. judge 准确率调优冒烟（PLAYwright-mcp 驱动 + API 轮询）：** global toggle ON + admin CHAT session。
- **场景1 KEEP_NEW**：r1「最喜欢 Java」→ 存 favorite_language=Java（block=偏好，clean）→ r2「更正更喜欢 Python」→ judge Jackson 解析 + temp 0.0 确定性判定 → conflict id=8 PENDING + askText 追加回复 → r3「保留 Python」→ interceptConflict route B→KEEP_NEW → RESOLVED，Python(id=42) 留 Java(id=41) 删。✓
- **场景2 FLAGGED**：r1 Java → r2 Python → PENDING id=9 → r3 无关「今天天气」→ route isAnswer=false → 双行共存打 FLAGGED（id=43/44 同 block 偏好 + conflict_id=9）→ status=FLAGGED。✓
- **结论**：原「同 Java/Python 场景有时标有时不标」非确定性消除。Jackson（杀 count-mismatch 静默漏判）+ temp 0.0 + key 归一 + 结构化 route 双场景稳定。fail-safe（任何解析失败→不冲突/不答，不丢事实）未触发。

**B. 前端检索节点 UI 冒烟（playwright-mcp 浏览器驱动）：** 见上「前端检索节点 UI 已落地」末尾「运行时冒烟」段。drag→config→save round-trip→连边→运行→sidecar 回调出证据 全链路绿。

**冒烟期误判的 2「瑕疵」— 深查后纠正根因 + 已修（见下「2 根因修复 已落地」）：**
1. **初判「save 清空 ragEnabled」→ 实为「VO 不暴露 ragEnabled」+ 测试 curl 用错 key**：冒烟时 curl PUT `/rag-enabled` body 发 `{"ragEnabled":true}`，但 `WorkflowController.setWorkflowRagEnabled` 读 `body.get("enabled")` → 取到 null → 写 null（DB 测试呈现「空」误导）。**用正确 key `{"enabled":true}` 复测：DB `rag_enabled=t` 持久，且跨 save update（`updateWorkflow` 仅 set name/desc/updatedBy + NOT_NULL 策略）保留**。真 gap 是 `WorkflowVO`/`WorkflowDetailVO` + `toWorkflowVO`/`getWorkflowDetail` builder 都**不含 ragEnabled** → 前端 GET 恒 null。前端无 workflow 级 toggle UI 是已知 gap（记忆模式开关 doc「Agent/Workflow detail 页 ragEnabled UI 未做」）。
2. **`listFlagged` VO 新候选 `id=null`（真 bug，已修）**：旧版 `listFlagged` 从 snapshot 文本（`readSnap`，createPending 时存，**不含 id** 因新行未插）重建新候选 → id 丢。FLAGGED 路径下新行已入库且 `flag()` 标新+旧 conflict_id。改用 `findByConflictId(c.id)` 取全组真实行 → `toCand` 带 id。复验两 candidate id 非空（45/46）。

**旁证（非 bug）：** `GET /memories/conflicts` 仅返 FLAGGED（PENDING 不返）— 设计如此（PENDING=锁会话等行内答，FLAGGED=待端点 resolve），先前误判为空 bug 已澄清。

**环境还原：** global toggle OFF（默认）/ `user_memories`+`memory_conflicts`+`rag_retrieval_logs` 清空 / 测试 workflow 删 / sidecar 停。backend(:8080)+frontend(:5173) 保留用户原运行状。

---

### 2 根因修复 已落地（✅ 2026-06-22，`mvn compile` BUILD SUCCESS + 复验绿）

范围 = 收口「运行时冒烟 已落地」误判的 2 瑕疵。

**Fix① WorkflowVO/DetailVO 暴露 ragEnabled（4 文件改）：**
- `workflow/dto/WorkflowVO.java` + `WorkflowDetailVO.java`：加 `private Boolean ragEnabled;` 字段。
- `workflow/service/WorkflowService.java`：`toWorkflowVO` + `getWorkflowDetail` 两个 builder 加 `.ragEnabled(workflow.getRagEnabled())`。
- **复验（✅）**：新建 wf `ragEnabled=None`（继承）→ PUT `/rag-enabled {"enabled":true}` → GET detail `ragEnabled=True` + GET list `ragEnabled=True`（修前恒 null）。DB `rag_enabled` 跨 save 保留（NOT_NULL 策略，非 save 清空）。
- **澄清**：`WorkflowController.setWorkflowRagEnabled` body 契约是 `{"enabled":...}`（非 `ragEnabled`）。前端 workflow 级 toggle UI 仍待（已知 gap，非阻塞；run 靠 global 或 PUT 端点设值）。

**Fix② listFlagged 用 findByConflictId 取真实 id（1 文件改）：**
- `chat/service/MemoryConflictService.java` `listFlagged`：弃「`selectBatchIds(old ids)` + `readSnap` 重建新候选（丢 id）」双路径 → 改 `memoryMapper.findByConflictId(c.id)` 单查取全组（FLAGGED 下新+旧行均带 conflict_id，见 `flag()`）→ `toCand` 带真实 id。`readSnap` 仍被 `flag()`/`resolve()` PENDING 路径用，保留。
- **复验（✅）**：重跑场景2 产 FLAGGED conflict 10 → GET `/memories/conflicts` 两 candidate id 非空（45=Java, 46=Python；修前 Python id=null）。

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

### 阶段7 已落地（✅ 2026-06-23，`mvn test` 281 绿 + `mvn test -Dsurefire.excludedGroups=` 含 PG 集成测 328 绿 + `mvn spring-boot:run` 解锁）

范围 = 一致性对账（ReconciliationJob 新特性）+ knowledge 包全套单测 + PG+pgvector 集成测脚手架 + v6 §10.2 验收。收口「开发期不写测试，全留阶段7」策略。

**A. 修陈旧测试（解 `mvn test-compile` 红灯 + `spring-boot:run` 要 `-Dmaven.test.skip`）：** 4 个测试跟构造/签名漂移：
- `LlmProviderServiceTest`/`TestConnectionTest`：`LlmProviderService` 构造 3→4 参（+`EmbeddingModelVersionMapper`，计划11 引入）→ 加第 4 mock。
- `RuntimeNodeCallbackServiceTest`：构造 5→8 参（+`RagScopeResolver`/`RagRetrievalService`/`RagModeResolver`，阶段5 引入）→ 加 3 mock + 8 处构造调用 replace_all。
- `MemoryServiceTest`：V27 记忆冲突重构后 `extractMemoriesAsync(long,String,String)` 删（换 `processMemory`）+ deps 全变（`memoryMapper/classifier/judge/conflictService`）→ 重写守 `buildMemoryContext` 契约（含 FLAGGED 前缀 + counterpart 聚合）。
- `ChatSessionServiceTest`：`@InjectMocks` 缺 6 个 RAG/记忆依赖（`ragModeResolver` 等 null → NPE）→ 补 6 `@Mock`，`resolve()` 返 primitive boolean 默认 false → 跳 RAG/记忆路径。

**B. 集成测脚手架（本地真实 PG16+pgvector，H2 跑不了 halfvec/HNSW/tsvector）：**
- 独立测试库 `agent_platform_it`（同 PG16 集群，复用 pgvector 0.8.2；一次性 `CREATE DATABASE`）。
- `application-it.yml`：datasource 指向 `agent_platform_it` + Flyway 净跑 V1..V28（验真实 migration + halfvec/HNSW DDL）+ Redis 真实 + `rag.visibility-cache/answer-cache.enabled=false`（确定性）+ `knowledge.index.poll-ms=999999999`（防 @Scheduled IndexJobWorker 抢测内 seed job 去 embed 无 key 报错）+ `runtime.gateway.mode=mock`（避 sidecar）。
- `AbstractIntegrationTest`：`@Tag("integration") @SpringBootTest @ActiveProfiles("it") @Import(TestSecurityConfig)` 基类。
- `pom.xml`：surefire `<excludedGroups>${surefire.excludedGroups}</excludedGroups>`（属性占位，默认 `integration`；CLI `-Dsurefire.excludedGroups=` 清空跑集成测）。`mvn test`=单测 only；`mvn test -Dsurefire.excludedGroups=`=全集。
- 主 `application.yml`：加 `spring.task.scheduling.pool.size:2`（@Scheduled 默认单线程池，IndexJobWorker + ReconciliationWorker 两轮询共存须 ≥2，防慢对账饿死 embed 轮询）。
- **Flyway 28 migrations 实测成功应用到 agent_platform_it**（含 V17 vector 扩展 + halfvec/HNSW DDL + 各 seed）。

**C. knowledge 包单测（96 测，纯 Mockito，默认 `mvn test` 跑）：**
- `RagConfigTest`（B1 effectiveContextCap=6000 + B3=D×cap + 常量锚）/ `CitationCheckerTest`（A1 正则：代码块剥离/越界→null/去重升序/`a[1]b`·`[123]`·`[1a]` 拒）/ `HalfVecUtilTest`（Locale.US 小数点 + dim 2048 + null 防御）/ `HashUtilTest`（sha256 确定性 + 已知向量 + null=空）。
- `AnswerCacheServiceTest`（P3 permissionSignature canonical：kbId 排序/ALL/EMPTY/排序 docIds；P2a verifyNodeHashes 失配；lookup gate/sim-floor-break/P3-mismatch-continue/命中 bumpUsage；store gate；异常吞）/ `RagRetrievalServiceTest`（forbidden gate / NO_VISIBLE_DOCS / NO_DENSE_HITS / B4 单 embed / A2 LOW_CONFIDENCE abstain — bestSim=parentL0Sim 非 rerankScore）/ `IndexJobWorkerTest`（I2 pre-embed re-check：node null/非 ACTIVE/hash 不匹配→voidJob；embed dim≠2048 抛→failJob）/ `IndexJobTxServiceTest`（failJob backoff DEAD vs PENDING 退避；completeUpsert I2 完成前复校；claim 状态机；doc→INDEXED — `@BeforeAll` init MP TableInfo 填 lambda 缓存使 `LambdaUpdateWrapper.getParamNameValuePairs()` 可断言）/ `RagScopeResolverTest`（P4 求交 + 同模型约束 + mode 派发 — `KnowledgeBase` 是 @Data 致两实例 .equals() 相等，`eq()` 跨实例串台，canRead stub 须用 `same()` 恒等）。

**D. 集成测（9 测，PG+pgvector，`mvn test -Dsurefire.excludedGroups=` 跑）：**
- `RagAnswerCacheMapperIT`（halfvec key_embedding insert + HNSW searchCandidates `<=>` 距离排序 + per-user 强制隔离 + status='ACTIVE' 过滤 + bumpUsage + deleteDecayed/countDecayed）。
- `ReconciliationIT`（seed drift/orphan/DEAD → scanKb 计数对 + purgeOrphanEmbeddings 删后 rescan=0 — 验 Phase E 特性 + 4 新 `KnowledgeIndexJobMapper` 法的 drift/orphan/dead SQL）。

**E. ReconciliationJob 新特性（v6 §7.3.6 最小对账 + §8.9a decay 兜底）：**
- `KnowledgeReconciliationReport` 实体（V17 表已建，非 BaseEntity，同 KnowledgeIndexJob）+ `KnowledgeReconciliationReportMapper`（+listRecentByKb 供未来管理 UI）。
- `ReconciliationTxService`（镜像 IndexJobTxService，每法 @Transactional 短事务无 LLM）：`scanKb`（聚合 total/drift/orphan/dead 计数 + 插报告行）/ `purgeDecayedAnswerCache`（循环 deleteDecayed 至空或 maxBatches）/ `purgeOrphanEmbeddings` / `enqueueReindexJobs`（**seam，autoRepair=false 默认不调**）。
- `ReconciliationWorker`（@Scheduled `${rag.reconciliation.poll-ms:600000}`，独立 `reconciliationTaskExecutor` core1/max2 不抢 embed 管线）：分批扫 ACTIVE KB → scanKb+落报告 → orphan>0 触发清理 → 全局批量清 answer_cache decay 行。异常吞不崩 scheduler。
- `ReconciliationProperties`（opt-in `enabled=false`/pollMs 600000/decayBatch 500/kbBatch 20/autoRepair=false）+ `ReconciliationTaskExecutorConfig`。
- 改 4 mapper：`KnowledgeIndexJobMapper`+4 法（countDeadFailedByKb/countStuckRunningByKb/findDriftedNodeIds drift join/countOrphanEmbeddings LEFT JOIN）/ `RagAnswerCacheMapper`+2 法（deleteDecayed 硬删非 status 翻 ARCHIVED—HNSW 索引会带 ARCHIVED 拖慢每次查 + countDecayed）/ `KnowledgeBaseMapper`+listActiveKbIds / `KnowledgeNodeMapper`+countActiveByKb / `KnowledgeEmbeddingMapper`+deleteOrphansByKb。
- `application.yml` `rag.reconciliation` 块（镜像 answer-cache 样式）。
- 单测 14：`ReconciliationTxServiceTest`（计数/批次循环/maxBatches 上限/seam no-op）/ `ReconciliationWorkerTest`（enabled gate/扫描/orphan 触发/空 KB 仍清 decay/异常吞）/ `ReconciliationPropertiesTest`（默认值锚）。
- ~~**REINDEX 自动修复出阶段7-minimal 范围**~~：~~`claimBatch` 只认 `job_type='UPSERT'`，今插 REINDEX 永不被消费 → 堆积~~ → **✅ 已闭环（必做收口 #7，2026-06-23）**：claimBatch 扩认领 UPSERT+REINDEX + enqueueReindexJobs 真实现（ON CONFLICT 幂等）+ repairDrift 入口 + worker scanBatch 接 autoRepair。`enqueueReindexJobs` 不再是 seam。autoRepair 默认仍 false（opt-in，启用担 re-embed 计费）。见下「八、必做收口」。~~**`rag_memory_facts` decay 出范围**~~（V17 表有 decay_at 但无 Java 实体/mapper，待该实体建加 sibling purge）→ **✅ 已闭环（必做收口 #8，2026-06-23）**：见下「八、必做收口」。

**F. H2↔pgvector 架构债收口（6 个全 context @SpringBootTest 迁 IT）：** post-RAG 全 Spring context 含 pgvector beans，H2 结构性加载不了（`llmConfig` init 查 `llm_providers.category` 缺列 → 50 错，V12-V28 schema 漂移累积，非本会话引入）。**5 个全 context 控制器测**（AgentController/AuthController/AuthIntegration/Execution/RuntimeCallback/Workflow）标 `@Tag("integration")` + 切 `@ActiveProfiles("it")` → 迁真 PG（Flyway 净跑后 context 正常加载，47 测全绿）。`AuthIntegrationTest` 加 `@AfterAll` 清 integrationuser（跨 run 可重复，否则残留用户致 step1 register 收 409）。**结论：需全 context = 需 pgvector = 集成测**；纯单测（Mockito 无 context）留默认 `mvn test`。

**v6 §10.2 九条验收映射：** ⑤per-user 缓存隔离→`RagAnswerCacheMapperIT` ✅ ⑦幂等重嵌+re-check→`IndexJobTxServiceTest`+`ReconciliationIT` ✅ ⑧Citation 硬校+abstain→`CitationCheckerTest`+`RagRetrievalServiceTest` A1/A2 ✅ ⑨token 上限→`RagConfigTest` B1 ✅；①②③④⑥（建库/上传/解析索引/L0L1L2/引用/版本/删文档清向量）需全栈，阶段3-6 冒烟已逐一验过（见各「已落地」冒烟段），playwright-mcp E2E runbook 见末尾「阶段7 验收 E2E」。

**最终验证：** `mvn test-compile` BUILD SUCCESS / `mvn test` **281 测 0 错**（单测，H2/none profile）/ `mvn test -Dsurefire.excludedGroups=` **328 测 0 错**（单测 H2 + 集成测 PG）/ `mvn spring-boot:run` 不再需 `-Dmaven.test.skip=true`。

---

## 四、后续阶段（未开始）

| 阶段 | 内容 | 状态 |
|------|------|------|
| 3 | `RagRetrievalService` 完整 8 步（核心）+ 不变式 | ✅ 完成（2026-06-19，冒烟全绿） |
| 4 | 权限可见集（Redis，USER+ROLE+DEPT 三层并集）+ answer_cache（per-user） | ✅ 可见集（4-A，2026-06-19）+ answer_cache(B)（2026-06-21，BUILD SUCCESS + 冒烟绿） |
| 5 | Chat/Agent/Workflow 集成（KB 绑定 scope，P4 求交） | ✅ 完成（2026-06-20，BUILD SUCCESS）；冒烟 retrieve/ask/CHAT/AGENT 绿 + 3 bug 已修；WORKFLOW(M5) 待跑 |
| 6 | 前端 `/knowledge` 页 + 检索调试面板 | ✅ 完成（2026-06-23）：MVP(2026-06-22) KB管理+文档上传+检索调试；**必做收口补全** RAG问答 SSE(#1)/检索审计表(#2)/记忆冲突列表(#3)/目录树端点(#4) 全落地，vue-tsc 绿。原 Defer 全收口 |
| 7 | 一致性对账 + 失效链路 + **全部单测/集成测收尾** + Phase1 验收 + E2E | ✅ 完成（2026-06-23）：ReconciliationJob 特性 + knowledge 全套单测(96) + PG 集成测脚手架 + 2 mapper/feature IT + 5 H2-context 测迁 IT；`mvn test` 281 绿 / `mvn test -Dsurefire.excludedGroups=`（含 PG 集成测）328 绿 / `mvn spring-boot:run` 不再需 `-Dmaven.test.skip` |

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

- [x] **V12–V28 + 本次改动提交 git（✅ 2026-06-22 推送 origin/main）**：5 主题 commit（migrations V12-V26 / backend RAG 核心+集成+记忆开关+answer_cache / frontend / sidecar / docs），merge origin/main 的 file-keeper 工作（多项目 monorepo，零文件重叠，干净 merge），push 成功。排除垃圾（backend/uploads + login-page.png 入 .gitignore）。
- [x] **M5 WORKFLOW 冒烟（✅ 2026-06-21 PASS）**：START→RETRIEVAL→END workflow + bind KB1 + rag_enabled → run → RETRIEVAL 回调 retrieveEvidence 返证据 `[1]` → EXECUTION_COMPLETED。修 sidecar callback response null→[] 校验。
- [x] **M6 记忆抽取实测（✅ 2026-06-20 PASS）**：global ON + CHAT msg → 4 行新记忆（name/age/occupation/favorite_language，conf 1.0 INFERRED）；会话 `ragEnabled=false` 负例→0 新行（门控正确）。环境已还原。
- [x] **judge 调优 + 前端检索节点 UI 运行时冒烟（✅ 2026-06-22 双绿）**：见 §〇「运行时冒烟 收口」+「运行时冒烟 已落地」。冒烟期误判 2「瑕疵」，深查纠正根因 + 已修（下 2 条）。
- [x] **修瑕疵①：WorkflowVO/DetailVO 暴露 ragEnabled（✅ 2026-06-22）**— 初判「save 清空」实为 VO 不返字段（+ 测试 curl 用错 key `ragEnabled` vs 控制器要的 `enabled`；DB 实际持久）。2 VO 加字段 + 2 builder 加 `.ragEnabled()`，复验 detail/list 返 True。前端 workflow 级 toggle UI 仍待（已知 gap）。
- [x] **修瑕疵②：`listFlagged` 新候选 id=null（✅ 2026-06-22）**— 弃 `readSnap` 重建（丢 id）改 `findByConflictId` 取全组真实行，复验两 candidate id 非空（45/46）。
- [ ] 部署目标 WinServer 2019 前置条件已文档化：`项目工程文档/WinServer2019部署前置条件.md`。

---

## 七、阶段7 验收 E2E runbook（v6 §10.2 ①②③④⑥，全栈 playwright-mcp 驱动）

> 自动化部分（⑤⑦⑧⑨）已由阶段7 单测/集成测覆盖（见上「阶段7 已落地」F 节映射）。本节为需全栈的人工/半自动 E2E 步骤，复用阶段6 前端知识库页冒烟的 playwright-mcp 套路（按 memory `feedback-browser-automation` 用 playwright 不用 camoufox）。

**前置：** backend(:8080) + runtime-sidecar(:8090) + frontend(:5173) 起，admin 录 Ark key（embedding+chat provider test-embed/test 通过），Redis 开。KB `smoke-kb`（id=1）有 INDEXED 文档。

**① 建库 / ② 上传解析索引 / ③ L0+L1+L2 生成：**
1. `/knowledge` → 「知识库管理」tab → 新建 KB（PER_SECTION 摘要）。
2. 文档抽屉拖拽上传 md → 轮询 PENDING→PARSING→SUMMARIZING→EMBEDDING→INDEXED。
3. psql 验：`knowledge_documents.status='INDEXED'` + 每 section 一 L0（`level='L0'`）+ 其 L2 子节点（`level='L2'`, `parent_id` 指向 L0）+ `l1_metadata` 非空 + `knowledge_index_jobs` 全 DONE + `knowledge_embeddings_doubao` 行数=L0 数 dim=2048 + I1 `e.content_hash=n.content_hash`=t。

**④ RAG 回答带引用：**
4. 「检索调试」tab → 选 KB + query「如何安装部署系统」→ SUPPORTED + 答案带合法 `[1]` + 引用表显文档标题 + 候选 L0 cosSim + 证据 L2 + token 预算。无关 query（「量子物理」）→ abstain LOW_CONFIDENCE 固定话术。

**⑥ 文档更新旧版本不进检索 / 删文档不再召回 + 向量清：**
5. 删文档 → psql 验 `knowledge_nodes` 软删（deleted=1）+ `knowledge_embeddings_doubao` 行 CASCADE 删（FK ON DELETE CASCADE，node_id）→ 重检索该 query → 不再召回（或召回空）。

**ReconciliationJob 冒烟（opt-in）：** `application.yml` 临时 `rag.reconciliation.enabled=true` + 起 backend → 手插 decayed `rag_answer_cache` 行（decay_at<now）+ drift node（改 node.content_hash 不更 emb）+ DEAD job → 等 poll-ms 或手触 → 验 `knowledge_reconciliation_reports` 新行（drift/orphan/dead 数对）+ decayed 行被删 + log「清理 decayed rows」。还原 `enabled=false`。

**环境还原：** 测后清 `rag_answer_cache`/`rag_retrieval_logs`/`knowledge_reconciliation_reports` 测试行 + toggle OFF + sidecar 停。

---

## 八、必做收口（用户 2026-06-23 追加：离"完全完成"的 8 项功能缺口）

> 依据 §四/§六 扫描得出的**真正未做**项（Phase2/可选/YAGNI/长期债不计入）。一项一项做，每完成一项更本文档。

| # | 项 | 类型 | 状态 |
|---|----|------|------|
| 8 | `rag_memory_facts` decay 兜底（sibling purge） | 后端 | ✅ 完成（2026-06-23，17 测绿） |
| 7 | ReconciliationJob autoRepair（claimBatch 扩 REINDEX + flip flag） | 后端 | ✅ 完成（2026-06-23，43 测绿） |
| 4 | `KnowledgeNodeController` 目录树端点 | 后端 | ✅ 完成（2026-06-23，3 测绿） |
| 5 | 前端 workflow 级 ragEnabled toggle UI | 前端 | ✅ 完成（2026-06-23，vue-tsc 绿） |
| 6 | 前端 Agent/Workflow detail 页 ragEnabled NSwitch | 前端 | ✅ 完成（2026-06-23，vue-tsc 绿） |
| 2 | 前端检索审计表 UI（`/knowledge/retrieval-logs`） | 前端 | ✅ 完成（2026-06-23，vue-tsc 绿） |
| 3 | 前端记忆/冲突列表 UI（`/memories` + `/conflicts`） | 前端 | ✅ 完成（2026-06-23，vue-tsc 绿） |
| 1 | 前端 RAG 问答 SSE（`/ask` CITATION consumer） | 前端 | ✅ 完成（2026-06-23，vue-tsc 绿） |

### #8 rag_memory_facts decay 兜底 已落地（✅ 2026-06-23，`mvn test` 17 测绿）

范围 = 闭环阶段7 对账遗留的 `rag_memory_facts` decay gap（V17 表有 `decay_at` 但 Java 零引用）。镜像 `rag_answer_cache` decay purge：建实体+mapper+sibling purge 接进 ReconciliationWorker。

**新增文件（2）：**
- `knowledge/entity/RagMemoryFact.java` — 非 BaseEntity（表无 deleted/version），标量列全映射，**key_embedding(halfvec) 不映射**（写入/检索须走自定义 SQL；当前仅 decay 扫删用不到）。镜像 `KnowledgeEmbedding`。
- `knowledge/mapper/RagMemoryFactMapper.java` — `deleteDecayed(batch)` 批量硬删 decay 过期 ACTIVE 行（子查询 LIMIT batch，避免 HNSW 带 ARCHIVED 拖慢）+ `countDecayed()` 计数。镜像 `RagAnswerCacheMapper` deleteDecayed/countDecayed。

**修改（2 代码 + 2 测）：**
- `ReconciliationTxService.java`：注入 `RagMemoryFactMapper`（构造器 5→6 参）+ 新 `purgeDecayedMemoryFacts(batchSize, maxBatches)`（循环 deleteDecayed 至空或 maxBatches，同 `purgeDecayedAnswerCache` 范式）。
- `ReconciliationWorker.poll()`：answer_cache purge 后加 `purgeDecayedMemoryFacts`（全局清，不依赖 KB）。
- `ReconciliationTxServiceTest`：+1 mock + 构造器 6 参 + 3 新测（loopsUntilEmpty / nothingToDelete / respectsMaxBatches）。
- `ReconciliationWorkerTest`：enabled + noKbs 两测加 `verify(txService).purgeDecayedMemoryFacts(...)` 锁行为。

**验证：** `mvn test -Dtest='ReconciliationTxServiceTest,ReconciliationWorkerTest,ReconciliationPropertiesTest'` → **17 测 0 错**（TxService 7→10 / Worker 5 / Properties 2）+ `mvn test-compile` BUILD SUCCESS。

**设计说明：** M2 语义软提示特性未启用 → 该表当前无生产者写入 → `purgeDecayedMemoryFacts` 调用通常返 0。实体+mapper+sibling purge 先就位，保证将来启用 M2 时无需再补对账路径（防御性闭环，非死代码）。`decay-batch` 配置复用 `rag.reconciliation.decay-batch`（answer_cache + memory_facts 共享批次大小，无新配置项）。

### #7 ReconciliationJob autoRepair 已落地（✅ 2026-06-23，`mvn test` 43 测绿）

范围 = 打通阶段7 标记的 drift→REINDEX 修复链路（原 `enqueueReindexJobs` 是 no-op seam，claimBatch 不消费 REINDEX，今插 job 会堆积）。现在 `autoRepair=true` 即自动修复 KB 漂移节点。

**修改（5 文件）：**
- `KnowledgeIndexJobMapper.java`：新 `insertReindexJobIgnoreConflict(j)` — `@Insert ... ON CONFLICT (idempotency_key) DO NOTHING`（idempotency_key UNIQUE，幂等：同 node+hash 已 PENDING/RUNNING/DONE 跳过）。仅写 node_id/kb_id/job_type='REINDEX'/content_hash/idempotency_key + now()，其余列（status/attempt/max_attempt/visibility_event）走 DB 默认。
- `IndexJobTxService.claimBatch`：`.eq(jobType,"UPSERT")` → `.in(jobType, List.of("UPSERT","REINDEX"))`。REINDEX 处理同 UPSERT（process() 重嵌 node.content + completeUpsert upsert 向量；REINDEX job 的 content_hash=node 当前值，drift 修复后向量 hash 对齐 node）。
- `ReconciliationTxService`：`enqueueReindexJobs` 真实现（drift node 逐个读 node，null/非 ACTIVE 跳过，content_hash=node 当前，idempotency_key=sha256(nodeId:contentHash:REINDEX)，调 insertReindexJobIgnoreConflict 累加返新入队数）+ 新 `repairDrift(kbId)`（findDriftedNodeIds→enqueueReindexJobs）。`@Transactional` 短事务无 LLM。
- `ReconciliationWorker.scanBatch`：`props.isAutoRepair() && r.getDriftCount()>0` → `txService.repairDrift(kbId)`。
- `ReconciliationProperties` javadoc + `application.yml` 注释：autoRepair 现功能可用（claimBatch 已消费 REINDEX），翻 true 即修复（担 re-embed 计费）。

**不变式保持：**
- **I2 re-check**：REINDEX job 走同一 process() 流程，embed 前 + completeUpsert tx 内两次复校 node.content_hash==job.contentHash；node 再变 → mismatch → voidJob（下一轮 scan 重发新 hash 的 job）。
- **I4 幂等**：idempotency_key UNIQUE + ON CONFLICT DO NOTHING → 同 node+hash 不重复入队；embedding.node_id UNIQUE ON CONFLICT 就地覆盖 → 重嵌不产多行向量。
- **dead/退避**：REINDEX job 复用 claimBatch 的 attempt+1 + failJob 指数退避（BACKOFF_BASE_SEC<<shift，cap 300）+ max_attempt→DEAD。

**决策：autoRepair 默认仍 false（不改 yml 默认值）。** 理由：① 与 `enabled`/`answer-cache`/`visibility-cache` 全 opt-in 哲学一致；② drift 修复 = re-embed = LLM 计费，静默默认开会产生成本；③ worker 整体 `enabled=false` 默认不跑，autoRepair 值在 enabled 前无意义。用户需修复时 yml 设 `rag.reconciliation.enabled=true` + `auto-repair=true` 即可（两 flag 都开）。若用户期望默认开 autoRepair，单行 yml 改即可。

**验证：** `mvn test -Dtest='ReconciliationTxServiceTest,ReconciliationWorkerTest,ReconciliationPropertiesTest,IndexJobTxServiceTest,IndexJobWorkerTest'` → **43 测 0 错**（ReconTxService 10→15：+4 enqueue 测 +2 repairDrift 测 -1 删 seam；ReconWorker 5→7：+2 autoRepair on/off 测；IndexJobTx 12/IndexJobWorker 7 claimBatch 扩认领不破现有 mock 测）。`mvn test-compile` BUILD SUCCESS。

**未跑（留后续）：** REINDEX 端到端 IT（需 enabled=true worker 跑 + Ark embed key 修真实 drift，重型，留冒烟）；ReconciliationIT（现有）只验 report-only 路径（autoRepair=false），不覆盖 repairDrift 真插 job。

### #4 KnowledgeNodeController 目录树端点 已落地（✅ 2026-06-23，`mvn test` 3 测绿）

范围 = 闭环阶段6 defer「目录树需先后端建 `KnowledgeNodeController` 端点」。文档大纲（L0 摘要 + L2 原文子节点）flat 列表端点，供前端 `/knowledge` 目录树 tab 建 n-tree。

**新增文件（3）：**
- `knowledge/dto/KnowledgeNodeVO.java` — flat 节点 VO（id/parentId/documentId/level/nodeType/title/tokenCount/status）。不暴露 content（L2 原文可能大，目录树只需标题）+ contentHash（内部不变式用，非 UI）。`@Data @Builder`。
- `knowledge/service/KnowledgeNodeService.java` — `listByDocument(docId, operatorId, admin)`：doc 存在性校（NOT_FOUND）+ canRead 门（doc 所属 KB，FORBIDDEN）+ 按 id 升序查（parser 按 section 顺序写，L0 先于其 L2 子节点 → 前端按 parentId 重建层级）。deleted 由 KnowledgeNode @TableLogic 自动滤。
- `knowledge/controller/KnowledgeNodeController.java` — `GET /api/knowledge/documents/{docId}/nodes` `@RequirePermission("knowledge:read")` → `R<List<KnowledgeNodeVO>>`。镜像 KnowledgeDocumentController 的 getCurrentUserId/isAdmin helper。

**测（3）：** `KnowledgeNodeServiceTest`（Mockito）— canRead 通过返 flat + L2.parentId 指向 L0 / canRead 拒抛「无权」且不查 node / doc 不存在抛 NOT_FOUND。@Data `KnowledgeBase` 实体 mock 用 `same()` 恒等（避 `eq()` 跨实例串台，见 memory `reference-rag-test-infra`）。

**决策：** 返回 flat 非 nested 树（REST 解耦 + 灵活，前端 n-tree 用 key/parentId 转换）。doc 级（非 KB 级）：目录树 = 文档大纲，KB 级可前端拉 docs 列表后逐 doc 拼。未含 hasEmbedding 字段（需 join embeddings，留后续 debug 增强）。

**验证：** `mvn test -Dtest='KnowledgeNodeServiceTest'` → **3 测 0 错** + `mvn test-compile` BUILD SUCCESS。

### #5 + #6 前端 workflow/Agent 记忆模式 toggle 已落地（✅ 2026-06-23，`vue-tsc --noEmit` EXIT 0）

范围 = 闭环「前端 workflow 级 toggle UI 未做」(#5) + 「Agent/Workflow detail 页 ragEnabled NSwitch 未做」(#6)。两处 NSwitch「记忆模式」调专用 rag-enabled 端点，与 ChatView session toggle 范式统一（label「记忆模式」+ size="small" + tooltip）。

**API 层（2）：**
- `frontend/src/api/workflow.ts`：加 `setRagEnabled(id, enabled)` → `PUT /workflows/${id}/rag-enabled` body `{ enabled }`（key 是 `enabled` 非 `ragEnabled`，对齐后端 `WorkflowController.setWorkflowRagEnabled` 契约）。
- `frontend/src/api/agent.ts`：加 `setRagEnabled(id, enabled)` → `PUT /agents/${id}/rag-enabled` body `{ enabled }`（同 key，写 Agent.config JSONB）。

**类型（1）：** `frontend/src/types/workflow.ts`：`Workflow` + `WorkflowListItem` 加 `ragEnabled?: boolean | null`（后端 WorkflowVO/DetailVO 已暴露，瑕疵①修复时加的字段，前端类型漏跟）。

**#5 workflow toggle（`WorkflowEditorView.vue`）：**
- topbar 状态 tag 后加 NSwitch「记忆模式」（`workflow-editor__rag-toggle` span）。
- ref `workflowRagEnabled`（load 时 `workflow.ragEnabled === true`，null/未设→off）+ `onWorkflowRagToggle(val)`（乐观 set ref → `workflowApi.setRagEnabled` → 成功 message / 失败回滚 prev）。
- 加 `NSwitch` import。

**#6 Agent toggle（`AgentDetailView.vue`）：**
- hero-meta（技能数 tag 后）加 NSwitch「记忆模式」（`agent-detail__rag-toggle`），`:disabled="!canManage"`（只读用户不能改）。
- computed `agentRagEnabled`：Agent.ragEnabled 存在 config JSONB（VO 无扁平字段）→ `parseAgentConfig(config).ragEnabled === true`（容错 null/非法 JSON→{}）。
- `onAgentRagToggle(val)`：乐观更新本地 `agentDetail.config`（重序列化带新 ragEnabled）→ `agentApi.setRagEnabled` → 成功 message / 失败回滚 prevConfig。
- `parseAgentConfig` 助手 + `NSwitch` import。

**设计决策：**
- 两端均用专用 rag-enabled 端点（非通用 update）——语义清晰 + 后端 `setRagEnabled` 单独处理（workflow 写列 / agent 写 config jsonb，含 `JsonbStringTypeHandler`）。
- 三态 null（继承全局）：NSwitch 二态无法显继承 → null 渲染为 off。用户要「继承」可不操作（保持默认）或后续加三态控件。与 ChatView session toggle 同处理。
- tooltip 文案区分覆盖层级：workflow「覆盖全局；检索节点回调受其约束」/ agent「覆盖全局」。

**验证：** `npx vue-tsc --noEmit` → **EXIT 0**（无类型错）。

**未跑（留后续）：** 浏览器冒烟（起 backend + 前端，拖 switch 验 PUT 200 + DB rag_enabled 持久 + 重载回显），复用 playwright-mcp 套路。

### #2 前端检索审计表 UI 已落地（✅ 2026-06-23，`vue-tsc --noEmit` EXIT 0）

范围 = 闭环阶段6 defer「检索审计表（retrieval-logs，knowledge:manage）」。`/knowledge` 页加第 3 个 tab「检索审计」，管理员可查 rag_retrieval_logs trace + 清理。

**新增文件（1）：**
- `frontend/src/components/knowledge/RetrievalAuditPanel.vue` — 审计面板：
  - **分页表**（remote）：id / 时间 / userId / mode / cragVerdict（tag，SUPPORTED→success / LOW_CONFIDENCE→warning / ERROR/CITATION_CHECK_FAIL→error）/ 查询（ellipsis tooltip）/ latencyMs / kbIds / 操作（详情+删除）。
  - **过滤栏**：userId / kbId（NInputNumber）/ mode（NSelect CHAT/AGENT/WORKFLOW/DEBUG）/ 时间范围（NDatePicker datetimerange）+ 查询/重置。
  - **清理**：行删（DELETE /{id}）+ 按时间批量清（DELETE ?before=ISO-8601，默认 7 天前保守，dialog 二次确认不可恢复）。
  - **详情抽屉**：NDescriptions 显 trace/用户/KB/模式/verdict/延迟/BM25 fallback/时间/查询 + 大 JSON 字段（tokenBudget/candidatesL0/evidenceL2）prettify 展示（max-height 滚动）。

**修改（2）：**
- `frontend/src/api/knowledge.ts`：加 `RagRetrievalLog` 类型（镜像后端 RagRetrievalLogVO，含大 JSON 字段）+ `RetrievalLogPageQuery` + 3 法 `pageRetrievalLogs(q)`/`deleteRetrievalLog(id)`/`deleteRetrievalLogsBefore(before)`（复用 `PageResult` from `@/api/admin`）。
- `frontend/src/views/KnowledgeView.vue`：加「检索审计」tab（`v-if="canManage"`，`authStore.hasPermission('knowledge:manage')`）+ import RetrievalAuditPanel + canManage computed。

**设计：** 审计含用户 query（近似 PII）→ 仅 knowledge:manage 可见，tab 对无权用户隐藏。verdict→color 映射对齐检索调试面板观察习惯。批量清默认 7 天前（保守，避免误清近期 trace）；管理员需更早可后续加自定义时间输入。

**验证：** `npx vue-tsc --noEmit` → **EXIT 0**（修 1 处 pagination.prefix 签名：itemCount `number|undefined` → 接收 optional）。

**未跑（留后续）：** 浏览器冒烟（起 backend，admin 登录→/knowledge→检索审计 tab→触发检索后验 trace 行 + 详情 JSON + 删除），复用 playwright-mcp。

### #3 前端记忆/冲突列表 UI 已落地（✅ 2026-06-23，`vue-tsc --noEmit` EXIT 0）

范围 = 闭环阶段6 defer「记忆/冲突列表」。用户长期记忆自服务查询/管理 + FLAGGED 冲突手动解决。

**新增文件（1）：**
- `frontend/src/components/chat/MemoryManagerPanel.vue` — 两区面板：
  - **我的记忆**（n-data-table）：分类（PREFERENCE/FACT/FEEDBACK tag）/键/值（ellipsis）/置信度（2 位小数）/来源/冲突（FLAGGED→⚠ counterpart 摘要）/更新时间/操作（删单条）。header-extra「清空全部」（dialog 二次确认 error 级）。空态 n-empty 提示「开启记忆模式对话后 AI 自动抽取」。
  - **记忆冲突**（n-card，仅 FLAGGED 非空时显）：按 conflict 分组 → block tag + askText + candidates 列表（category/key/value）+ 4 解决按钮（保留新/保留旧/都保留/全删，对应 KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD，loading 防重）。

**修改（2）：**
- `frontend/src/api/chat.ts`：加 `UserMemory`/`MemoryCandidate`/`MemoryConflict` 类型（镜像后端 UserMemoryVO/MemoryCandidateVO/MemoryConflictVO）+ 5 法 `listMemories`/`deleteMemory(id)`/`clearMemories()`/`listMemoryConflicts()`/`resolveMemoryConflict(id, decision)`。
- `frontend/src/views/ChatView.vue`：rag-toggle 旁加「记忆」按钮（quaternary）→ NDrawer(width 720) 挂 MemoryManagerPanel；加 `NDrawer/NDrawerContent` import + `showMemory` ref。

**设计：** 记忆按 current userId 隔离（用户私有资产，无需 knowledge/chat 权限），挂 ChatView 抽屉（记忆在对话语境最相关，发现性好）。冲突解决 4 选项对齐后端 `MemoryConflictResolveRequest.decision` 枚举；解决后双刷新（冲突 + 记忆表，因 KEEP_* 会改记忆行）。空态引导用户开记忆模式。

**验证：** `npx vue-tsc --noEmit` → **EXIT 0**。

**未跑（留后续）：** 浏览器冒烟（起 backend + 开记忆模式对话产记忆 → 点「记忆」验列表 + 冲突分组 + 解决按钮），复用 playwright-mcp。

### #1 前端 RAG 问答 SSE 已落地（✅ 2026-06-23，`vue-tsc --noEmit` EXIT 0）

范围 = 闭环阶段6 defer「RAG 问答区（/ask CITATION consumer）」。`/knowledge` 加「RAG 问答」tab，多 KB 选 + query → 流式答案 + 引用标注。

**新增文件（1）：**
- `frontend/src/components/knowledge/RagAskPanel.vue` — 流式问答面板：
  - **输入区**：KB 多选（NSelect，loadBases 拉列表）+ query textarea（enter 提交）+ 提问/停止按钮（停止=AbortController.abort）。
  - **流式消费**：迭代 `askStream` → CHUNK 追加 `answer`（whitespace-pre-wrap）/ CITATION 解析 JSON → citations 列表 / ERROR 显错 / DONE 收尾。thinking 态（asking 且无 answer）显 NSpin「检索与生成中…」。
  - **引用列表**：CITATION 事件 content=RagCitation[] → `[n]` tag + 标题 + doc#/node# 元信息。
  - abstain（无可检索范围）：后端返单 CHUNK 文案，前端照常显（无 citation）。

**修改（2）：**
- `frontend/src/api/knowledge.ts`：加 `AskStreamEvent` 类型 + `askStream(query, kbIds, signal)` 异步生成器（fetch SSE + reader 逐事件 yield，镜像 `workflowApi.runStream` + chat store CHUNK 解析范式）+ `parseAskSseEvent` 助手（按 `data:` 行拼 JSON）。加 `getStorage/STORAGE_KEYS` import（JWT 注入）。
- `frontend/src/views/KnowledgeView.vue`：加「RAG 问答」tab + import RagAskPanel。

**设计：** 复用既有 `RagCitation` 类型（index/documentId/title/nodeId，与 CITATION JSON 对齐）。KB 多选走后端 P4 求交（用户权限 ∩ 所选），无可见 KB → abstain 文案。AbortController 组件 unmount 时 abort 防泄漏。答案暂纯文本渲染（whitespace-pre-wrap），未接 markdown 渲染器（留后续，对齐 MessageBubble 若需富文本）。

**验证：** `npx vue-tsc --noEmit` → **EXIT 0**。

**未跑（留后续）：** 浏览器冒烟（起 backend + Ark key，/knowledge→RAG 问答→选 KB→问「如何安装部署系统」→验 CHUNK 流式 + CITATION 引用 [1] 标注），复用 playwright-mcp。








