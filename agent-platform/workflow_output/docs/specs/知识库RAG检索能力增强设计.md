# 规格 · 知识库：RAG 检索能力对齐顶级（七项增强）

> Phase 1 设计规格。实现必须与本文一致；来源为 2026-09-03 人工测试问题 14x 未解决项 #1/#2 + 世界顶级知识库检索对比差距清单（七项）。
> 关联：[企业级精准知识库RAG 总览](企业级精准知识库RAG-总览.md)｜[知识库模型选择与保密权限设计](知识库模型选择与保密权限设计.md)｜[企业级精准知识库RAG Feature Map](../feature-map/企业级精准知识库RAG.feature-map.md)。

## 0. 用户已拍板决策（2026-09-03）

| 决策点 | 拍板结果 |
|---|---|
| 范围 | 七项全做（C1-C7），单份规格一个大版本交付，内部按工作包分批实施 |
| 附件注入（C2） | 描述承担召回 + 命中后按需内容注入（文本类全文/图片实时视觉描述），引用标「📎 原件」 |
| 关联关系（C1） | 四种全做（必须引用/按需引用/必须被引用/按需被引用）+ 链式限 1 跳 |
| 上下文嵌入（C4） | 新文档/重建文档生效；存量不强制、提供可选重建 |
| 多模态（C5） | 务实路线（VLM 图文描述索引）为主 + ColPali 实验通道为辅，双路线都设计 |

## 1. 目标

对标 2026 世界顶级知识库检索（Glean 企业知识图谱 / Microsoft GraphRAG / Anthropic Contextual Retrieval / ColPali 多模态 / Agentic RAG），在既有混合检索+rerank+Grounded Answer+评估中心基线之上补七项能力：

| # | 能力 | 一句话 | 对标 |
|---|---|---|---|
| C1 | 文档关联关系图 | 文档间四种引用关系，召回后关系图后处理（强制注入/进 rerank） | Glean 知识图谱、关系先验 |
| C2 | 附件式整文件召回 | 文件/图片整件入库不切片，描述决定召回，命中后内容按需注入 | Claude Projects 整文档注入 |
| C3 | Agentic 多轮检索 | 激活休眠的补检索/邻居扩展/覆盖校验，有界循环 | CRAG/Self-RAG 迭代检索 |
| C4 | 上下文嵌入升级 | 规则版前缀 → LLM 生成 chunk 定位语再向量化 | Anthropic Contextual Retrieval |
| C5 | 多模态检索 | 图片原生向量索引 + PDF 页面级视觉嵌入实验通道 | ColPali、多模态嵌入 |
| C6 | 连接器生态 | 定时从外部源（URL 站点/S3/WebDAV）同步文档进库 | Glean connectors |
| C7 | 全局语义问答 | 库级摘要 + 全局问题 map-reduce 模式 | GraphRAG Global Search |

## 2. 现状事实（调研 2026-09-03）

| 事实 | 位置 |
|---|---|
| 检索管道刻意**线性 8 步无循环**（step1→3→4→5→6→7→8） | `RagRetrievalService.java:48` |
| step6 候选池 = L0 锚定子节点 + L1 文档锚定子节点 + BM25 兜底 + OpenSearch 生产通道合并；**无关系扩展步骤** | `RagRetrievalService.java:272-275` |
| **休眠代码**：`RetrievalRouter.supplement()` 多查询补检索原型、`NeighborExpander`、`CoverageVerifier` 主链路零引用；QueryPlanner 输出 NEIGHBOR 策略但 OpenSearch 网关只处理 EXACT/SPARSE | `retrieval/RetrievalRouter.java:6-8`、`context/NeighborExpander.java:4`、`context/CoverageVerifier.java`、`retrieval/OpenSearchProductionRetrievalGateway.java:32` |
| QueryPlanner 纯规则（正则+关键词分类 COMPARISON/EXACT/PROCEDURE/LIST/SEMANTIC），**非 LLM** | `query/QueryPlanner.java:17-45` |
| HyDE + 多释义查询扩展已有（1 次 LLM 生成 K 释义 + 1 假想答案多路召回） | `service/QueryExpansionService.java:19-27` |
| **规则版上下文前缀已存在**：Contextualizer 拼「文档/版本/标题路径/所属背景」前缀，contextHash 贯穿索引 job；**非 LLM 生成**的 chunk 级定位说明 | `Contextualizer.java:24-36`、`IndexJobWorker.java:139-153` |
| 图片只走转文本：AUTO=视觉模型识图→文字入库、MANUAL=手填；检索 query 仅文本 | `DocumentParserService.java:256-315` |
| docType(IMAGE/FILE) × indexMode(MANUAL/AUTO) 并入 parse_options；FILE+MANUAL=整文件+手填描述不解析（**附件召回的雏形**），但枚举无 ATTACHMENT，命中只进描述文本、原件仅 fileRef 引用不进上下文 | `KnowledgeDocumentService.java:94-96,136-150,222-229`、`dto/RagRetrieveVO.java:54-58` |
| 聊天附件定向召回（V130）是**聊天消息附件**注入开关，与知识库附件召回是两回事 | `chat/service/internal/MemoryRecallPipeline.java:96,232` |
| 文档间关系：全库 grep 零命中；migration V17/V36/V101~V118 无关系表；节点唯一层级是文档内树形 parent_id | `V17__create_knowledge_rag.sql:101` |
| embedding 走 OpenAI 兼容 `/embeddings`（纯文本入参），模型可 per-KB（qwen3-vl-embedding 等模型本身具备多模态潜力，**协议层未传图**） | `llm/provider/OpenAICompatibleProvider.java:202`、`LlmGateway.embed` |
| 原件字节保留 + inline/attachment 取回端点已有（IMAGE inline / FILE attachment） | `IndexJobWorker.java:244-257`、`KnowledgeDocumentService.java:254-274` |
| OpenSearch 第二索引：快照/别名/灰度切换、chunk 写入带 pipelineVersion | `opensearch/KnowledgeIndexManager.java`、`IndexAliasService.java`、`IndexJobWorker.java:160-170` |
| @Scheduled 轮询 worker 模式成熟（FOR UPDATE SKIP LOCKED 双节点互斥）：IndexJobWorker / MemoryAssetIngestWorker / ReconciliationWorker | `chat/service/internal/MemoryConsolidationWorker.java:33` 注释、`ReconciliationWorker.java:44` |
| SSRF 校验器已有可复用：`assertFetchSafe`（回源前校验 URL） | `media/service/MediaStorageService.java:125-127` |
| 语义缓存：per-user + permission_signature + evidence hash 懒失效 | `service/internal/AnswerCacheService.java:24-39` |
| 评估中心：黄金集/Recall/MRR/nDCG/反馈审核/发布门禁 | `V115__rag_evaluation_center.sql`、`evaluation/*` |
| 保密库（V131 confidential）：成员仅 RAG 问答出口，asset/nodes/检索调试全 403 | `知识库模型选择与保密权限设计.md` §5 |

## 3. C1 文档关联关系图

### 3.1 数据模型（Flyway）

新表 `knowledge_document_relations`：

```sql
CREATE TABLE knowledge_document_relations (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,              -- 关联仅限同库（首版边界，见 3.5）
  doc_id BIGINT NOT NULL,             -- 主动方文档
  related_doc_id BIGINT NOT NULL,     -- 被动方文档
  relation_type VARCHAR(32) NOT NULL, -- MUST_CITE / MAY_CITE / MUST_BE_CITED / MAY_BE_CITED
  note VARCHAR(500),                  -- 可选备注（为什么关联）
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uq_kdr UNIQUE (kb_id, doc_id, related_doc_id, relation_type),
  CONSTRAINT ck_kdr_no_self CHECK (doc_id <> related_doc_id)
);
CREATE INDEX idx_kdr_doc ON knowledge_document_relations(kb_id, doc_id);
CREATE INDEX idx_kdr_related ON knowledge_document_relations(kb_id, related_doc_id);
```

四种关系语义（沿用用户原提案）：

| relation_type | 语义 | 检索行为 |
|---|---|---|
| MUST_CITE 必须引用 | 命中 A 时 B 强制一起进上下文（硬绑定、打包召回） | 召回后处理：直接注入，token 预算最高优先 |
| MAY_CITE 按需引用 | B 作为候选进入 rerank | 召回后处理：追加进 rerank 队列（标注 boost 来源） |
| MUST_BE_CITED 必须被引用 | 只要 B 被召回，A 必须跟着出现（免责条款跟法条） | 召回后处理：查 B 的反向 MUST_CITE，等价实现 |
| MAY_BE_CITED 按需被引用 | 弱关联，作「相关文档」推荐露出，不进主上下文 | 不进证据；检索调试/引用尾部展示「相关文档」区 |

实现口径：MUST_BE_CITED(A↔B) 在建边时**同时物化**为反向 MUST_CITE(B→A) 两个方向的查询视图（存储仍一行，查询时双向解释）；MAY_BE_CITED 同理反向解释为 MAY_CITE。即**存储只需 CITE 方向语义 + 双向读**，避免四种类型两两组合的状态爆炸。

语义等价去重：建边时若**语义等价的反向边已存在**（如已存在 (B,A,MUST_CITE)，再建 (A,B,MUST_BE_CITED)），服务层拒绝并提示「等价关联已存在」（唯一约束拦不住语义重复，服务层必须拦）。

### 3.2 召回后关系图后处理（管道新 step6.5）

位置：`RagRetrievalService` rerank 完成后、证据组装前；**在 C3 迭代循环全部轮次结束后对最终合并命中集执行一次**（循环内各轮不做关系扩展，防止同一边重复带出与重复 rerank 调用）。流程：

1. 取最终命中集合 H（rerank 后、阈值过滤前）；
2. 查 `knowledge_document_relations` where doc_id ∈ H（批量 IN，1 跳**硬限制**，不做 BFS 传导——A→B→C 的 C 不再扩展）；
3. **去重**：关系带出文档已在 H 则跳过（天然防环：限 1 跳后 A⇄B 双向边只表现为一次注入）；
4. **权限复校**：带出文档对当前用户逐个 `canRead` 复核（复用 per-KB ACL 判定），无权限文档**静默丢弃**（不报错、不提示存在——防权限探测侧信道），保密库成员对带出文档同样走 RAG 出口语义；
5. 分类注入：MUST_CITE 直接进证据集（标记 `injectedBy=RELATION_MUST`）；MAY_CITE 追加进 rerank 队列尾部重打分（标记 `injectedBy=RELATION_MAY`，能过阈值才进）；
6. token 预算不足时优先级：**必须引用 > 原始命中 > 按需引用**（挤占顺序从 MAY_CITE 开始）；
7. Trace：step6.5 计量关系扩展耗时/带出数/被权限过滤数，入 RagTraceContext。

> **实现注（WP1 Step2 落地口径，2026-09-03）**：
> ① 缓存失效（条 4 之外的第三路）落 `computeKnowledgeSnapshot` SQL 双聚合（nodes `id:hash:status` ‖ 边 `doc:related:type` 双段 md5）——P3 校验链单点收口，不另拆 evidence hash 段；算法变更部署日存量缓存全量 miss 一次属预期。
> ② 「入 RagTraceContext」调整为：MDC traceId 结构化日志一条（边/MUST/MAY/相关文档/权限丢弃/耗时）+ `rag_retrieval_logs.evidence_l2` 行增 `injectedBy` 字段——RagTraceContext 设计为只存 ID/用途，加计量列需 DDL 新表，收益不成比例。
> ③ MAY_CITE「追加进 rerank 队列重打分」复用 `rankWithTrace`（真实 RankingEngine 调用）；ranking DISABLED 模式下候选保守淘汰（无重排信号不硬闯）；重打分异常降级丢弃 MAY，不伤主链。
> ④ 插入位置定在**软拒答判定之后**：关联不带救 LOW_CONFIDENCE（拒答口径不因边改变）。
> ⑤ MUST 分 = 原始 topK 最高分 + ε：过 EvidencePolicyService/CoverageSelector 的 score 排序选择，条数预算挤不掉「必须引用」。

### 3.3 关联推荐（防图谱维护膨胀）

复用既有 trace 的共召回记录：后台任务（@Scheduled，低频如每日）统计同 query 下共现文档对 ≥ N 次（默认 3）且未建边的，写 `knowledge_document_relation_suggestions`（doc_a, doc_b, co_recall_count, sample_query_hash）；库 owner 在关联管理页看到建议列表，一键采纳（建边）或忽略。**只建议、不自动建边**。

### 3.4 管理 UI（前端）

- 文档抽屉新增「关联」Tab：本文档出边/入边列表（关系类型徽标 + 对方标题 + 删除）；「添加关联」= 选同库文档 + 四选一关系类型 + 备注；
- 关联建议列表页（owner/canManage 可见）；
- 检索调试面板证据区：`injectedBy=RELATION_*` 的证据带「🔗 关联带出」徽标；MAY_BE_CITED 反向解释结果在尾部「相关文档」区展示。

### 3.5 边界与不做

- 首版关联**仅同库**（跨库边涉及跨库权限矩阵与保密库穿透，远期）；
- 链式传导限 1 跳（用户提案坑 #1：防 token 爆炸）；
- 不做自动实体抽取建图（那是 GraphRAG 全自动路线，本文 C1 是人工声明式轻量图；自动抽取列为远期）。

## 4. C2 附件式整文件召回

### 4.1 模式定义

`indexMode` 新增枚举值 `ATTACHMENT`（存于 parse_options，无 DDL）：整个文件入库**不切片**，用户必填「附件描述」（≤4000 字，决定何时召回）+ 可选「检索关键词」。docType 任意（IMAGE/PDF/FILE 均可挂此模式）。

与现有 FILE+MANUAL 的区别：MANUAL 的手填文本=索引内容本身（答案可能直接引用描述文本作证据）；ATTACHMENT 的描述只承担**召回匹配**，命中后走 4.2 的按需内容注入——回答基于**文件真实内容**，描述只是路由器。

### 4.2 命中后按需内容注入（用户拍板：描述+按需内容注入）

证据组装阶段（step7 前）对 `indexMode=ATTACHMENT` 的命中节点：

| docType | 注入内容 | 实现 |
|---|---|---|
| 文本类（txt/md/csv/json/源代码等白名单内纯文本） | 原件全文，≤8000 字；超限截断+标注「已截断，原件可下载」 | 原件字节直接读（已保留），零模型调用 |
| PDF/DOCX/XLSX | 入库时 Tika 提取的全文（复用现有解析器）缓存，命中时取 ≤8000 字 | 入库时一次提取存 node metadata.attachment_text |
| IMAGE | 命中时实时调视觉模型生成内容描述（≤500 字），**结果缓存** | Redis 缓存 key=sha256(fileRef+visionModel+promptVer)，TTL 30 天，版本/替换自动失效（fileRef 变） |

- 注入内容进上下文时标注来源结构：`[附件 {originalName}] 内容：…`；引用渲染为「📎 原件」（复用 CitationVO.fileRef 链路，docType 徽标已有）。
- token 预算：附件注入内容计入证据预算，单个附件注入上限 8000 字（可配 `rag.attachment.inject.max-chars`）。
- 保密库：附件命中注入内容**只出现在 RAG 问答出口**（本就如此——注入发生在 RAG 管道内），fileRef 下载链路维持保密库 403 语义，互不破坏。

### 4.3 前端

- 上传弹窗 indexMode 三选：智能解析（AUTO）/手动索引（MANUAL）/**附件模式（ATTACHMENT）**；ATTACHMENT 时表单=描述必填 + 关键词可选 + 原件上传；
- 文档列表 ATTACHMENT 行徽标「📎」；检索调试命中附件型证据显示注入的内容预览（截断）。

### 4.4 边界

- 不做音视频附件内容注入（无转写管线，远期；描述召回已可用）；
- 图片实时视觉描述缓存未命中时增加一次 VLM 调用（~1-3s），计入答案合成预算与计费归户（docOwner 口径同 embed）；
- ATTACHMENT 文档不参与 L0/L1/L2 层级（无章节），L1 摘要=描述本身。

## 5. C3 Agentic 多轮检索（激活休眠代码）

### 5.1 有界循环

打破「线性无循环」约束，改为**有界迭代**（用户问题不缺证据时行为与现状完全一致）：

```
round 0: 现有完整管道（step1→8）
round 1..MAX_ROUND(默认2, 可配 rag.retrieval.max-rounds):
  CoverageVerifier 判定证据是否覆盖 query 全部子意图
  ├── 覆盖 → 跳出
  └── 缺口 → RetrievalRouter.supplement() 生成补充 query（继承原权限/版本/KB 范围）
             → 重跑 step3-6（召回+融合+rerank）→ 候选并入（去重 by nodeId）
  预算守卫：累计证据 token 超 budget 或轮次耗尽 → 跳出
```

- 激活 `NeighborExpander`：rerank 后对「边界证据」（首尾段、被截断的 TABLE 节点）扩展相邻节点（QueryPlanner 已会输出 NEIGHBOR 策略，OpenSearch 网关补 NEIGHBOR 处理或在 PG 侧按 sibling 序号扩展）；
- 循环内每轮 trace 分轮计量（`RagTraceContext` 加 round 维度）；
- 拒答路径不进循环（INSUFFICIENT 判定在循环耗尽后）。

### 5.2 QueryPlanner 升级（LLM 可选开关）

- 新增配置 `rag.queryplanner.llm.enabled`（默认 false 灰度）：开启时 LLM 生成 QueryPlan（分类+子意图拆解+filters+策略集），规则版为兜底与降级路径（LLM 失败/超时 2s 内回退规则版）；
- 子意图拆解结果供 5.1 的 supplement 与 CoverageVerifier 使用（对齐 agentic RAG 的 query decomposition）。

### 5.3 边界

- 不做无上限自主 agent 循环（成本不可控）；MAX_ROUND=2、每轮 query 数 ≤3；
- 不做多 agent 协作检索（远期）。

## 6. C4 上下文嵌入升级（规则版 → LLM 版）

### 6.1 现状与差距

现有 Contextualizer 是**规则版**前缀（文档/版本/标题路径拼接，零 LLM 调用）；Anthropic Contextual Retrieval 的 LLM 版为每个 chunk 生成「该块在全文档语境中位置」的定位说明再 embed，[检索失败率 -49%~-67%](https://www.anthropic.com/engineering/contextual-retrieval)。

### 6.2 设计：每文档一次 LLM 调用生成 chunk 定位表（非逐 chunk 调用）

逐 chunk 调用成本 O(chunks)，改在 SUMMARIZING 阶段（L1 摘要同批）**每文档 1 次** LLM 调用：输入=L1 摘要+全 chunk 清单（id+标题+首行），输出=每 chunk 一句定位语（≤50 字，JSON 数组）。落库：

- `knowledge_nodes` 增列 `contextual_text TEXT`（定位语），contextHash 语义升级=sha256(规则前缀+定位语+原文)（复用现有 contextHash 失效链路，hash 变→索引 job 自动重嵌）；
- embed 文本 = 规则前缀 + 定位语 + 原文（规则前缀保留——零成本且稳定；LLM 定位语补充语义锚点）；
- `pipeline_version` bump（如 `CTX_LLM_V1`），OpenSearch chunk 文档同步携带。

### 6.3 生效范围（用户拍板）

- 适用对象：**解析型文档**（AUTO/MANUAL 的多节点文档）。ATTACHMENT 附件型文档豁免（单节点无 chunk 语义，描述即索引内容，见 C2）；

- 新文档/新版本：默认生效；
- 存量文档：不强制；库 owner 可在「重建索引」入口选择「应用 LLM 上下文增强」（复用换 embedding 重建机制，job 全量重生成定位语+重嵌，UI 强提示 token 成本预估）；
- 配置开关 `rag.contextual.llm.enabled`（默认 true，可关回纯规则版）。

### 6.4 边界

- LLM 定位语生成失败/超时：降级纯规则版（contextHash 按实际文本算，不阻塞索引）；
- 定位语不含权限治理信息（沿用 Contextualizer 现有注释约束）。

## 7. C5 多模态检索

### 7.1 路线 A（主路线）：图片原生向量索引

- 协议扩展：`OpenAICompatibleProvider` EMBEDDING 行增加多模态入参支持（content 数组 text/image_url 混排，兼容 DashScope multimodal-embedding 类 API）；`LlmGateway` 增 `embedMultimodal(List<ContentPart>, model, owner)`；
- 索引：IMAGE 文档（AUTO/MANUAL/ATTACHMENT 均可）在文本向量之外**追加图片向量**：`knowledge_embeddings` 增列 `modality VARCHAR(16) DEFAULT 'TEXT'`（值 TEXT/IMAGE），同一 node 可挂双向量行；
- 检索：query 仍为文本 → 文本 query 向量同时打 TEXT/IMAGE 两种向量行（同模型维度校验，混维度模型拒绝双路）；RAG 候选池新增 IMAGE 向量通道，RRF 融合（k=60 同参）；
- 命中 IMAGE 向量行的证据：内容=该图既有文本描述（识图/手填/附件描述），引用带图（现有 fileRef inline 链路）。

### 7.2 路线 B（实验通道）：ColPali 页面级视觉嵌入

- 适用：扫描版 PDF/图文混排报表（表格图表版面信息 OCR 丢失场景）；
- 部署：自托管 ColPali sidecar（推理服务，GPU 可选 CPU 慢速灰度），PDF 入库时逐页渲染图 → sidecar 返回页级多向量（patch 级 late interaction，即「页面切成小块各自有向量、查询时逐块打分取最大和」）；
- 存储：pgvector 不适合多向量 MaxSim（「查询与页面各块逐一取相似度最大值再求和」的打分方式），多向量与打分逻辑放 OpenSearch 自定义 script score 或 sidecar 内置重排端点；
- 接入：作为第 4 检索通道进 RRF（标注通道来源），仅 `rag.visual.colpali.enabled=true` 且 KB 级开关开启的库启用；
- 定位：实验通道，走影子对比（复用 V117 影子机制）验证增益后再转正，默认关。

### 7.3 边界

- 不做「以图搜图」query（用户传图检索，远期）；
- 路线 A 依赖所配 embedding 模型真支持图输入：不支持时该库自动只有 TEXT 通道（配置校验提示，不报错）；
- ColPali sidecar 不在本版交付范围（接口预留+设计文档，部署另立运维项）。

## 8. C6 连接器生态与定时同步

### 8.1 数据模型（Flyway）

```sql
CREATE TABLE knowledge_connectors (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  tenant_id BIGINT NOT NULL,
  kb_id BIGINT NOT NULL,
  type VARCHAR(32) NOT NULL,        -- URL_SITE / S3 / WEBDAV
  name VARCHAR(128) NOT NULL,
  config_cipher TEXT NOT NULL,      -- 加密配置（endpoint/凭证/路径规则），AES-GCM 应用主密钥
  schedule_cron VARCHAR(64) NOT NULL DEFAULT '0 0 4 * * *',
  status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',  -- ENABLED/DISABLED/ERROR
  last_sync_at TIMESTAMP,
  last_sync_summary VARCHAR(1000),
  created_by BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT now(),
  updated_at TIMESTAMP
);
CREATE TABLE knowledge_connector_docs (
  id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  connector_id BIGINT NOT NULL,
  external_id VARCHAR(512) NOT NULL,  -- URL/S3 key/WebDAV path
  etag VARCHAR(256),                  -- 内容指纹（ETag/Last-Modified/hash）
  doc_id BIGINT NOT NULL,             -- 映射到 knowledge_documents
  synced_at TIMESTAMP NOT NULL DEFAULT now(),
  CONSTRAINT uq_kcd UNIQUE (connector_id, external_id)
);
```

### 8.2 连接器 SPI 与首批实现

```
interface KnowledgeConnector {
  ConnectorType type();
  List<ExternalDoc> list(FetchCursor cursor);   // 增量枚举（etag 过滤）
  byte[] fetch(ExternalDoc doc);                // 下载内容
  void close();
}
```

- **URL_SITE**：种子 URL + 同域爬取（深度 ≤2、单次 ≤50 页、仅 text/html/md/pdf 白名单后缀）；每 URL 过 `assertFetchSafe`（复用 media 侧 SSRF 校验：拒内网地址/非常规协议/重定向到内网）；
- **S3**：endpoint/bucket/prefix/凭证，ListObjects 增量（etag 变更检测）；
- **WebDAV**：目录枚举 + etag。

同步 worker：复用 @Scheduled + FOR UPDATE SKIP LOCKED 认领模式（对齐 IndexJobWorker）；流程=增量枚举 → etag 对比（新增/变更→下载→走**现有解析+索引管线**；源端删除→可选「源删同步删」，默认标记 ISOLATED 走既有隔离治理而非硬删）。

### 8.3 权限与安全边界

- 同步内容进指定 KB 后，**权限沿用该 KB 的 ACL**（不做源系统权限实时映射——Glean 级 permission-aware 同步列远期，本版明确不做）；
- 凭证 AES-GCM 加密落库（复用应用主密钥体系），日志/trace 永不打明文凭证；
- 下载内容过现有文件类型白名单与大小限制；SSRF 校验同 8.2；
- 同步产生的新文档 created_by=连接器创建者（计费归户口径一致）。

### 8.4 前端

- KB 详情新增「连接器」Tab：新建（类型+配置表单+同步周期 cron 预设：每小时/每天/每周）、启停、立即同步、最近同步结果（新增/更新/删除计数+错误摘要）；
- 文档列表来源徽标「🔌 同步」+ external_id 展示。

### 8.5 边界

- 首批三连接器；不做飞书/SharePoint/Confluence 等 SaaS OAuth 连接器（需要 OAuth 应用与回调设施，远期另立规格）；
- 不做双向同步（只读拉取，不改源端）。

## 9. C7 全局语义问答（sensemaking）

### 9.1 库级摘要（L-KB 层）

新表 `knowledge_base_summaries`（kb_id, version, summary TEXT, topics JSONB, stats JSONB, generated_at, UNIQUE(kb_id, version)）：库内全部文档 L1 摘要 map 阶段分批浓缩（每批 ≤20 个 L1）→ reduce 成库级摘要（≤2000 字）+ 主题清单。触发：库文档数变更 ≥10% 或距上次 >7 天，@Scheduled 低频任务，走影子对比验证后再用于线上。

### 9.2 全局问题模式

QueryPlanner 新分类 `GLOBAL`（「总结/趋势/整体/全库/有多少类…」类问题）→ 分支：

1. **map-reduce**：取库内全部文档 L1 摘要分批（每批 ≤15）→ LLM 每批提要点 → 合成答案（对齐 GraphRAG Global Search 动态社区选择思路，以 L1 为社区单元，不做 Leiden 社区聚类——L1 已是天然「社区摘要」）；
2. 引用降到**文档级**：`[1]《文档标题》`（无段落锚点），CitationChecker 增文档级校验模式（引用的 doc 必须真实在本次 map 批次中出现过）；
3. 库级摘要作为答案开头的「库概览」段（标 L-KB 来源，不占引用编号）；
4. 混合问题（既要全局又要细节）：GLOBAL 分支先出概览，再自动跟进一次局部检索轮（复用 C3 循环），两类证据分列。

### 9.3 边界

- 全局模式单库生效（kbIds 多选时取首库，提示用户缩小范围）；
- 保密库成员可正常使用全局问答（内容出口仍是 RAG 答案本身，摘要不单独下发——L-KB 摘要不暴露给任何 API，仅注入 prompt）；
- 不做跨库全局聚合（远期）。

## 10. 架构落点与文件结构

### 10.1 后端（com.superprogrammer.knowledge）

```
knowledge/
├── relation/                    # C1 新增
│   ├── DocumentRelationService.java      # 建边/删边/查询（双向解释）
│   ├── RelationGraphPostProcessor.java   # step6.5 召回后关系处理
│   └── RelationSuggestionWorker.java     # 共召回建议（@Scheduled）
├── attachment/                  # C2 新增
│   ├── AttachmentContentInjector.java    # 命中后按需注入（文本/图片分流）
│   └── AttachmentVisionCache.java        # Redis 视觉描述缓存
├── retrieval/                   # C3 激活+扩展
│   ├── RetrievalRouter.java               # supplement() 接入主链
│   └── IterativeRetrievalOrchestrator.java # 有界循环编排（新）
├── query/
│   └── LlmQueryPlanner.java              # C3 LLM 规划器（开关+降级）
├── multimodal/                  # C5 新增
│   ├── ImageEmbeddingChannel.java        # IMAGE 向量通道
│   └── ColpaliGateway.java              # 实验通道接口（sidecar 预留）
├── connector/                   # C6 新增
│   ├── KnowledgeConnector.java           # SPI
│   ├── UrlSiteConnector.java / S3Connector.java / WebDavConnector.java
│   └── ConnectorSyncWorker.java          # @Scheduled 轮询认领
├── global/                      # C7 新增
│   ├── KbSummaryWorker.java               # L-KB 生成（@Scheduled）
│   └── GlobalAnswerStrategy.java          # map-reduce 分支
└── service/
    ├── Contextualizer.java               # C4 升级（LLM 定位表接入）
    └── IndexJobWorker.java               # C4 contextual_text / C5 IMAGE 双向量
```

### 10.2 前端（src/views/knowledge 及 api）

- `DocumentRelationPanel.vue`（关联 Tab）、`ConnectorPanel.vue`（连接器 Tab）、上传弹窗 ATTACHMENT 选项、检索调试 RELATION/ATTACHMENT 徽标与轮次展示、api/knowledge.ts 扩展。

### 10.3 迁移文件

按序号顺延（4 个文件）：

1. `V1xx__knowledge_document_relations.sql` —— C1 两表：`knowledge_document_relations` + `knowledge_document_relation_suggestions`；
2. `V1xx__knowledge_rag_context_multimodal.sql` —— C4/C5 列：`knowledge_nodes.contextual_text`、`knowledge_embeddings.modality`；
3. `V1xx__knowledge_connectors.sql` —— C6 两表：`knowledge_connectors` + `knowledge_connector_docs`；
4. `V1xx__knowledge_base_summaries.sql` —— C7 表。

## 11. 测试策略

| 层 | 用例 |
|---|---|
| 后端单测 | **C1**：四关系建边/双向解释/自环拒绝/重复边拒绝；step6.5 去重、1 跳限制（A→B→C 的 C 不带出）、权限复校丢弃（无读权带出文档静默消失）、token 优先级挤占顺序；建议生成（共召回 ≥N 触发）。**C2**：ATTACHMENT 校验（描述必填/长度）、文本类注入全文/超限截断标注、图片走缓存命中不调 VLM、保密库附件 fileRef 403 但问答注入正常。**C3**：覆盖判定真/假分支、MAX_ROUND 耗尽、补充 query 继承权限范围、预算守卫、LLM 规划器失败 2s 回退规则版。**C4**：定位表 JSON 解析失败降级纯规则、contextHash 变更触发重嵌、pipeline_version 携带。**C5**：双 modality 向量行写入、混维度模型拒双路、不支持图输入模型仅 TEXT。**C6**：SSRF 拒内网/拒重定向内网、凭证加密落库（密文不含明文）、etag 增量（新/变/源删→ISOLATED）、并发认领互斥。**C7**：GLOBAL 分类、map 批次文档级引用校验、摘要不下发 API |
| 后端集成 | 检索管道回归：无关联/非附件/覆盖足够时行为与基线逐字节一致（防激活循环引入回归）；全链 trace 分轮/分通道计量存在 |
| 前端单测 | 关联 Tab 装配、ATTACHMENT 表单校验、连接器表单/cron 预设、徽标渲染 |
| 人工/playwright | 建边「术语表 MUST_CITE 差旅制度」→ 问差旅问题，回答同时带术语表引用；附件模式传架构图+描述→问图内容→回答含图中信息+📎；扫描件 PDF ColPali 通道（实验开关开）检索命中表格；URL 连接器同步一个静态站点→文档入库可检索；「总结整个库」触发全局模式文档级引用 |
| 评估门禁 | C1-C4 各建 ≥5 例黄金集用例（关联带出/附件注入/补检索轮/上下文增强各设 expected chunk + forbidden chunk），进 V115 评估中心跑 Recall/MRR 对比基线，发布门禁不降 |

## 12. 安全策略（增量）

| 风险 | 对策 |
|---|---|
| C1 关系带出越权（权限穿透） | step6.5 逐文档 canRead 复校，静默丢弃；保密库带出文档仅 RAG 出口语义 |
| C1 权限探测侧信道 | 丢弃不报错不提示；关联建议仅 owner/canManage 可见 |
| C2 附件内容泄露 | 注入只在 RAG 管道内；保密库 fileRef/asset 403 链路不放松；图片视觉描述缓存带 fileRef 失效 |
| C5 图片内容合规 | 图片向量仅索引级使用；命中证据内容仍走既有识图文本（不新增暴露面） |
| C6 SSRF/凭证 | assertFetchSafe 全 URL 生效；AES-GCM 加密；白名单+大小限；trace 不落凭证 |
| C6 供应链内容 | 同步内容走与手工上传完全相同的解析/白名单/隔离治理管线 |

## 13. 性能目标（增量，不回归既有基线）

| 链路 | 目标 |
|---|---|
| 检索调试（纯检索） | 维持 ~1-2s；step6.5 关系扩展 ≤150ms（批量 IN + 索引）；C3 循环只在缺覆盖时触发，覆盖时 0 开销 |
| RAG 问答 P95 | 维持现状（图片附件未命中缓存时 +≤3s，缓存命中 0 增量） |
| 索引入库 | C4 每文档 +1 次 LLM 定位表调用（与 L1 同批，预算 ≤2000 token 出）；C5 每图 +1 次 embed |
| 连接器同步 | 单库单轮 ≤50 文档、限速 1 req/s（URL_SITE），不影响在线检索 |
| L-KB 摘要生成 | 低峰执行（cron 默认 04:00 后），失败重试不阻塞 |

## 14. 分批实施建议（同一大版本内工作包顺序）

1. **WP1 = C1+C2**（直接闭掉 14x 两个未解决项，人工可验价值最高）；
2. **WP2 = C3**（激活休眠代码，回归风险集中，需逐字节基线对比）；
3. **WP3 = C4**（索引管线改动，与 C3 解耦）；
4. **WP4 = C7**（复用 L1 与 C3 循环设施）；
5. **WP5 = C5**（协议扩展+双通道，ColPali 只留接口）；
6. **WP6 = C6**（连接器，独立新模块，最后落）。

## 15. 边界与不做（总）

- 不做全自动实体抽取知识图谱（C1 为人工声明式；自动建图远期）；
- 不做跨库关联边、跨库全局聚合；
- 不做源系统权限实时映射（连接器权限=目标 KB ACL）；
- 不做 SaaS OAuth 连接器（飞书/SharePoint 等，远期另立规格）；
- 不做以图搜图、音视频转写附件、双向同步；
- ColPali sidecar 部署运维不在本版交付（接口预留）。

## 16. 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| 关系图后处理 | 召回完文档后，按人工维护的「谁必须跟着谁」关系表补带文档 | 命中法条→免责条款自动跟上 |
| 1 跳 | 关系只展开一层，A 带出 B 就停，不带出 B 的关联 C | 防 token 无限膨胀 |
| 侧信道 | 从系统的报错/提示/耗时等旁侧信息推断本不该知道的事 | 无权文档被带出时若报错，用户可试探哪些关联存在 |
| 按需内容注入 | 检索命中附件后，把文件真实内容（全文/视觉描述）临时塞进 prompt | 问「架构图里网关在哪」→图中信息进上下文 |
| Agentic 检索 | 让检索像人一样「查一轮→看够不够→不够再换词查」的循环 | 问对比题→第一轮缺 B 产品→自动补查 B |
| 有界循环 | 循环有硬上限（轮次/token），防止越查越贵 | 最多补 2 轮就强制出答案 |
| contextual retrieval | 给每个文本块生成一句「它在全文哪里、讲什么」的定位语，再和原文一起做向量 | 「第三条」单独无语义，加「《差旅制度》报销标准节」后可被语义命中 |
| 定位表 | 一次 LLM 调用为整篇文档所有块各生成一句定位语的 JSON 结果 | 20 个块 1 次调用出 20 句 |
| late interaction（迟交互） | 页面切成小块各自有向量，查询时逐块打分取最优再汇总的打分方式 | ColPali 对 PDF 页 1024 个小块各自比对的 MaxSim |
| MaxSim | 查询词向量与页面每个块向量取最大相似度再求和的分数 | 「表格里 Q3 数据」命中页内表格块 |
| modality（模态） | 信息形态：文字/图片/音频 | 同一节点挂 TEXT 和 IMAGE 两种向量行 |
| 连接器 | 定时从外部系统（网站/网盘）拉文档进知识库的适配器 | 每天凌晨拉公司 Wiki 最新页面 |
| etag | 源端给内容打的指纹，变了就是内容变了 | S3 文件 etag 没变→跳过不重下 |
| SSRF | 诱导服务器去访问它自己内网地址的攻击 | 连接器填 http://192.168.0.1 被拒 |
| map-reduce | 先分片各自总结（map）再汇总成终稿（reduce） | 50 篇文档分 4 批总结→合成库级摘要 |
| sensemaking | 对全库做「整体理解/趋势总结」类问题 | 「这个库整体讲什么管理制度」 |
| L-KB | 库级摘要层：整个知识库一段浓缩摘要 | 对标 GraphRAG 的社区摘要 |
| 影子对比 | 新旧两套检索同时跑只记录不生效，用数据证明新版更好再切 | V117 机制，ColPali 通道走此验证 |
