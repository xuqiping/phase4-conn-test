# Feature Map · 企业级精准知识库 RAG

> Phase 3 持续维护的粗略功能-代码速查表。当前重点复核 P2～P5 的生产链路真实性；文档接近 5000 tokens 时拆分技术清单。
> 需求编号：RAG-FR-01、RAG-FR-02、RAG-FR-03、RAG-FR-06、RAG-FR-09。

## 一句话描述

把上传文档变成可追溯版本、可精确定位、带权限治理且能稳定重建索引的结构化知识节点。

## 代码位置总览

| 类型 | 路径 | 作用说明 |
|---|---|---|
| 文档治理 | `backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeDocumentService.java` | Canonical Document、不可变版本、生效/撤销及治理字段 |
| 解析入口 | `backend/src/main/java/com/superprogrammer/knowledge/service/DocumentParserService.java` | 按文档类型解析并串联解析产物与节点写入 |
| 结构协议 | `backend/src/main/java/com/superprogrammer/knowledge/service/internal/ExtractedDocument.java`、`Section.java` | 统一标题树、阅读顺序和精确 locator |
| 结构提取 | `backend/src/main/java/com/superprogrammer/knowledge/service/internal/StructuredDocumentExtractor.java` | PDF、Markdown、DOCX、图片结构化定位 |
| Excel 提取 | `backend/src/main/java/com/superprogrammer/knowledge/service/internal/ExcelSheetExtractor.java` | Sheet、行和 Cell 范围定位 |
| 解析产物 | `backend/src/main/java/com/superprogrammer/knowledge/service/internal/ParseArtifactService.java` | 大 JSON 写文件存储并以 SHA-256 校验 |
| 分块策略 | `backend/src/main/java/com/superprogrammer/knowledge/chunk/` | D0/S1/C2/E3 类型化策略注册表 |
| 节点写入 | `backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeNodeWriter.java` | 双轨映射、邻居关系、治理继承和持久化 |
| 运维指标 | `backend/src/main/java/com/superprogrammer/common/metrics/BizMetrics.java` | 固定低基数分块数量和耗时指标 |
| 上下文化 | `backend/src/main/java/com/superprogrammer/knowledge/service/Contextualizer.java` | 稳定拼装模型输入并计算 content/context Hash |
| 索引任务 | `backend/src/main/java/com/superprogrammer/knowledge/service/IndexJobWorker.java`、`service/internal/IndexJobTxService.java` | 版本复核、任务快照、事务完成与重试幂等 |
| 数据迁移 | `backend/src/main/resources/db/migration/V103__rag_document_version_governance.sql`～`V108__knowledge_parse_artifact.sql` | 文档版本、治理和解析产物引用演进 |
| 测试 | `backend/src/test/java/com/superprogrammer/knowledge/` | 版本、解析、分块、节点和 PostgreSQL 集成验证 |
| OpenSearch 配置 | `backend/src/main/java/com/superprogrammer/knowledge/config/OpenSearchProperties.java`、`OpenSearchConfig.java` | 可关闭的官方客户端、环境变量认证与连接超时 |
| OpenSearch 健康检查 | `backend/src/main/java/com/superprogrammer/knowledge/config/OpenSearchHealthIndicator.java` | 区分 disabled/up/down，诊断信息不暴露认证信息 |
| 索引规格 | `backend/src/main/java/com/superprogrammer/knowledge/opensearch/KnowledgeIndexSchema.java` | 版本化物理索引名、向量维度校验、严格 mapping |
| 索引与 Alias | `KnowledgeIndexManager.java`、`IndexAliasService.java` | 创建物理索引并原子切换/回滚 read/write alias |
| OpenSearch 双写 | `OpenSearchChunkDocument.java`、`OpenSearchChunkWriter.java` | C2 Dense/Sparse/ACL/版本副本、bulk 逐项失败识别 |
| OpenSearch 对账 | `OpenSearchReconciliationService.java`、`ReconciliationWorker.java` | 缺失/孤儿/Hash/ACL 漂移、dry-run 修复与删除传播 |
| 索引控制面 | `KnowledgeIndexOperationsService.java`、`KnowledgeAdminController.java`、`frontend/src/components/knowledge/IndexOperationsPanel.vue` | 状态、预检、切换和回滚 |
| 索引快照持久化 | `V112__rag_index_snapshot_registry.sql`、`KnowledgeIndexSnapshotMapper.java`、`DatabaseSnapshotStore.java` | snapshot 登记与 active/previous route 以 PG 为真相源，服务重启不丢失 |
| 实际快照重建 | `V113__rag_index_snapshot_physical_name.sql`、`V114__rag_snapshot_rebuild_jobs.sql`、`KnowledgeIndexRebuildService.java`、`DatabaseKnowledgeIndexRebuildGateway.java` | 创建隔离物理索引、按 snapshot 入队、聚合进度、取消与 READY 门禁 |
| QueryPlan | `backend/src/main/java/com/superprogrammer/knowledge/query/QueryPlanner.java`、`QueryPlan.java` | 规则优先识别精确/比较/流程/列表/语义问题 |
| 召回 Pre-filter | `backend/src/main/java/com/superprogrammer/knowledge/retrieval/RetrievalFilterBuilder.java` | tenant/KB/ACL/status/version 强制前置过滤 |
| 多通道召回 | `RetrievalCandidate.java`、`ProductionRetrievalGateway.java`、`OpenSearchProductionRetrievalGateway.java`、`RrfFusion.java` | OpenSearch Exact/Sparse 强制 Pre-filter + PG Dense/BM25 兼容合并、降级与统一重排 |
| Ranking Engine | `backend/src/main/java/com/superprogrammer/knowledge/ranking/`、`RagRetrievalService.java` | LLM/RERANK/DISABLED Provider 已接生产链路；候选白名单、正文相关性、严格 JSON 与显式模型 |
| Rerank/时间线 | `ModelRerankProvider.java`、`RagRetrieveVO.java`、`RetrievalDebugPanel.vue` | capability fail-closed 与 QueryPlan/RRF/Ranking 调试协议 |
| 动态证据覆盖 | `backend/src/main/java/com/superprogrammer/knowledge/context/CoverageSelector.java`、`EvidenceBudget.java` | 按问题类型选择 2～20 条并限制单文档挤占 |
| 证据策略真实接线 | `backend/src/main/java/com/superprogrammer/knowledge/context/EvidencePolicyService.java`、`service/RagRetrievalService.java`、`dto/RagRetrieveVO.java` | PG 最终复核后统一执行动态预算、Hash 去重、来源多样性、token 上限，并返回六态置信字段 |
| 覆盖补检索 | `CoverageVerifier.java`、`retrieval/RetrievalRouter.java` | 缺项检测、1/2 轮上限、继承原 FilterContext |
| 上下文组装 | `ContextBuilder.java`、`NeighborExpander.java` | PG 复核结果、Hash 去重、授权邻居和 token 硬预算 |
| 引用校验 | `citation/CitationVerifier.java`、`service/internal/CitationChecker.java`、`RagRetrieveVO.java` | locator、权限/版本/Hash、Claim 支持度 |
| Citation locator 真实接线 | `mapper/RagRetrievalQueryMapper.java`、`dto/RagQueryRow.java`、`service/RagRetrievalService.java` | PG 最终复核同时读取节点 metadata，解析 page/article/sheet/cellRange/bbox，经 CitationVerifier 后写入响应 |
| Grounded Answer | `answer/GroundedAnswerService.java`、`ConfidenceEvaluator.java` | 分批事实携带引用、配置化六态置信协议 |
| Grounded Fact 编排 | `answer/GroundedAnswerService.java` | 有界分批、Citation 白名单、事实去重合并与同主题多值冲突检测 |
| Grounded Answer 真实生成 | `service/RagRetrievalService.java`、`llm/dto/LlmRequest.java`、`llm/LlmGateway.java` | `/retrieve` 先事实提炼再答案合成；调用用途分别写入同一 RAG trace、模型日志和计费链路 |
| `/ask` Grounded SSE | `controller/KnowledgeAskController.java`、`chat/dto/StreamEvent.java`、`frontend/src/api/knowledge.ts`、`RagAskPanel.vue` | 完成校验后按 CHUNK→CITATION→RAG_STATE→DONE 输出，禁止未校验 token 提前泄露 |
| RAG 评测中心 | `evaluation/RagMetricsCalculator.java`、`EvaluationService.java`、`V115__rag_evaluation_center.sql` | 黄金集、run/result 与 Recall/MRR/nDCG 指标；V110 已被认证迁移占用 |
| 评测 Dataset/Case 领域服务 | `evaluation/EvaluationService.java` | tenant/KB 归属、逐行容错 JSONL 导入、脱敏结构导出、Repository 边界 |
| 发布门禁/影子 | `evaluation/ReleaseGateService.java`、`retrieval/ShadowRetrievalService.java` | 指标阈值、采样/预算控制且不影响用户答案 |
| 评测管理界面 | `KnowledgeEvaluationView.vue`、`EvaluationRunPanel.vue`、`ShadowComparisonPanel.vue`、`KnowledgeView.vue` | 管理员启动异步评测、查看 Champion/Challenger 与发布门禁状态 |
| 稳定灰度与回滚 | `migration/RagRolloutService.java`、`RagRolloutReadinessService.java`、`RagModeResolver.java`、`KnowledgeAdminController.java`、`IndexOperationsPanel.vue` | 按 KB/用户稳定分桶，后端权威门禁，回滚时恢复路由并失效答案缓存 |
| 反馈与运维指标 | `evaluation/FeedbackReviewService.java`、`FeedbackReviewMapper.java`、`RagFeedbackController.java`、`BizMetrics.java`、`RagAskPanel.vue`、`V111__rag_feedback_review_queue.sql` | 反馈只进待审核队列；召回/重排/覆盖/降级/删除 SLA 使用低基数指标 |

## 关键调用链路

```text
上传/更新文档
  → KnowledgeDocumentService 创建不可变版本
  → DocumentParserService 选择结构化解析器
  → ParseArtifactService 保存解析 JSON 与 Hash
  → ChunkFactory 按 Section 类型生成 S1/C2/E3
  → KnowledgeNodeWriter 继承版本、ACL、密级并建立父子邻居
  → IndexJobWorker 进入向量化/索引任务
```

## 技术清单与原理注解

### 1. Canonical Document + 不可变版本

- **采用技术**：主记录指针、不可变版本行、事务锁与乐观并发检查。
- **一句话原理**：文档名称和归属放主档案，正文每次更新都新建一份版本，绝不覆盖历史。
- **大白话案例**：像合同档案柜，封面代表合同本身，里面每次修订都另存一版，当前有效版只是一枚可切换书签。
> 批注：任何“更新文件”都不能覆盖旧 `fileRef/sourceHash`；撤销当前版本必须清空当前指针。

### 2. 结构化解析产物

- **采用技术**：统一 Section 协议、文件对象存储、SHA-256 完整性校验。
- **一句话原理**：先把不同格式翻译成同一种带坐标的目录树，再交给分块器。
- **大白话案例**：像把 PDF 页码、Excel 单元格和 Word 标题都翻译成统一快递地址，后续引用可以准确找回原位置。
> 批注：拿不到 bbox 就留空，禁止编造坐标；大 JSON 不直接塞数据库行。

### 3. 策略注册式 D0/S1/C2/E3 分块

- **采用技术**：Strategy + Factory、完整段落聚合、原子类型路由、稳定邻居 path。
- **一句话原理**：不同内容使用不同切法，切完仍保留它属于哪章、前后是谁以及源文档在哪。
- **大白话案例**：普通文章可以按段落装箱，但法规条款、FAQ 答案和表格一行像不可拆的成套零件，不能从中间锯开。
> 批注：迁移期 S1/C2/E3 分别映射旧 L0/L2，真实粒度写 metadata；禁止跨章节凑 300 token。

### 4. 低基数指标与脱敏日志

- **采用技术**：Micrometer Counter/Timer、固定 granularity tag、结构化摘要日志。
- **一句话原理**：只统计类型、数量和耗时，不把正文或每个文档 ID 变成监控维度。
- **大白话案例**：仓库日报只写“今天处理多少箱、平均多久”，不把每箱货物全文抄进日报。
> 批注：禁止记录 Prompt、Query、Chunk 正文、密钥和完整模型输出。

### 5. Contextual Content + 版本指纹 Outbox

- **采用技术**：稳定文本规范、双 Hash、Outbox、`ON CONFLICT DO NOTHING`、任务配置快照。
- **一句话原理**：入队时把“这段知识、它的背景、用哪套解析/分块/模型/Pipeline”一起封存，重试永远按原配方执行。
- **大白话案例**：像生产工单不仅写零件编号，还把图纸版本、机器型号和工艺版本钉在单上；第二天机器设置变了，旧工单也不会偷偷换配方。
> 批注：Worker 调模型前与写向量事务内都要复核 content/context Hash；权限和密级不得拼进模型文本。

### 6. 可关闭的 OpenSearch 连接基座

- **采用技术**：OpenSearch Java Client、Spring `ConfigurationProperties`、Actuator `HealthIndicator`。
- **一句话原理**：只有管理员明确启用时才创建客户端；健康检查把未启用、可连接和连接失败分成三种状态。
- **大白话案例**：像给新仓库装总闸和指示灯，总闸没开时不会误报事故，开闸后绿灯代表连通、红灯代表需要排查。
> 批注：密码只允许来自环境变量；健康详情和日志禁止出现用户名、密码、Authorization 或完整异常正文。

### 7. 版本化物理索引与双 Alias

- **采用技术**：严格 mapping、物理索引快照、read/write alias、原子 Alias Actions。
- **一句话原理**：先完整建好新索引，再一次性把读写路标从旧仓库切到新仓库，失败时按反向动作回滚。
- **大白话案例**：像搬仓库先把新仓库装满并验货，最后一次性更换导航牌，而不是边搬边让客户找不到货。
> 批注：物理索引名不得包含模型昵称；不同向量维度禁止混入同一索引；mapping 强制包含 tenant/KB/ACL/status/version/hash。

### 8. 一次向量化、PG + OpenSearch 双写

- **采用技术**：稳定 node ID 幂等 upsert、NDJSON Bulk API、逐项响应解析、可选 Spring sink。
- **一句话原理**：同一向量同时服务旧 PG 与新检索副本，OpenSearch 任一条失败都不把任务标成完成。
- **大白话案例**：像一张入库单要同时登记总账和货架系统；货架登记失败就保留工单重做，重做仍覆盖同一货位，不多出一箱。
> 批注：OpenSearch 文档只保存检索必要 ACL token 和治理字段；失败日志只写 node ID，不写 Chunk 正文。

### 9. 权威 PG 与检索副本对账

- **采用技术**：快照集合比较、确定性修复计划、REINDEX Outbox、delete-by-query。
- **一句话原理**：PG 是总账，OpenSearch 是可重建副本；缺失和漂移重建，孤儿删除，先 dry-run 再执行。
- **大白话案例**：像仓库盘点，用总账逐项核对货架：少的补、错标签的重贴、多出来的清走。
> 批注：ACL 或可见性变化优先清除整个 KB 检索副本，宁可短暂缺结果也不能返回旧权限内容。

### 10. 管理员蓝绿控制面

- **采用技术**：权限注解、登记 snapshot、二次确认、Vue 管理 Tab。
- **一句话原理**：管理员只操作系统登记的快照编号，由服务生成真实索引动作，不能把任意物理索引名传给后端。
- **大白话案例**：像机房切换只能选资产系统里的已验收服务器，不能在输入框里随便敲一个地址接管流量。
> 批注：所有写操作要求 `knowledge:manage`；切换和回滚必须明确确认，后续审计沿用平台操作日志体系。

### 11. 隔离快照全量重建

- **采用技术**：PG 持久化重建状态、Outbox 目标快照字段、OpenSearch 隔离物理索引、状态聚合。
- **一句话原理**：重建任务把数据写进新仓库，全部成功后才允许把 Alias 路牌切过去。
- **大白话案例**：装修新门店时先在新店完成上货盘点，旧店继续营业；新店没验收绝不改导航地址。
> 批注：重建任务禁止写线上 write alias；物理索引名只能由后端 `KnowledgeIndexSchema` 生成并登记；FAILED/CANCELLED 快照不可切换。

## 数据库迁移速查

- `V103` 起建立文档版本治理；`V107` 增加治理字段；`V108` 增加解析产物信息；`V109` 增加任务版本指纹；`V112`～`V114` 增加快照路由、真实物理索引和目标快照任务字段。
- `knowledge_documents`：一行代表一个主文档，`current_version_id` 是当前有效版书签。
- `knowledge_document_versions`：一行代表一次不可变修订，并保存其文件与解析产物证据。
- `knowledge_nodes`：当前仍使用旧 L0/L2 物理层级，新 D0/S1/C2/E3 协议保存在 metadata，等待后续 OpenSearch 双写迁移。
> 批注：已执行 Flyway 不可修改；任何结构演进必须新增高于 V108 的迁移。

## 相关文档

## P5 评测数据集真实链路

- `V115__rag_evaluation_center.sql`：建立 Dataset、Case、Run、Result 四张表；Dataset 按 tenant/KB/name 唯一，Run/Result 预留汇总指标和 trace。
- `EvaluationMapper` + `PostgresEvaluationRepository`：把领域对象持久化到 PostgreSQL，读取通过 tenant 与 dataset 联合校验。
- `EvaluationService`：负责数据集校验、JSONL 逐行容错导入、无 Chunk 正文导出。
- `KnowledgeEvaluationController`：提供 `/api/knowledge/admin/evaluation/**`，统一要求 `knowledge:manage`，写操作进入审计日志。
- `knowledge.ts` + `EvaluationRunPanel.vue`：调用真实创建、导入和列表接口；尚未实现的异步运行不再用本地状态冒充。

大白话：Dataset 像一本固定试卷，Case 是每道题；现在已经能把试卷和题目真实存进数据库，后续 Run 才是拿某个 Pipeline 真正答卷并计算成绩。

> 导出只含评测字段，不携带知识 Chunk 正文；接口不把完整问题写入 Java 日志。

- `EvaluationRunService`：异步执行固定 Case，状态为 QUEUED/RUNNING/COMPLETED/FAILED；逐题关联 trace，汇总检索、引用、忠实度和拒答指标。它当前是显式装配的领域类，待 PostgreSQL 与生产 Pipeline 适配器完成后再注册运行时 Bean。
- `RagEvaluationPipeline`：调用生产 `RagRetrievalService` 的 PRECISION 链路，把真实 traceId、Evidence nodeId、置信状态和引用校验结果转换成评测 Outcome；模型解析仍遵循显式选择和管理员默认规则。
- `EvaluationRunConfiguration`：专用有界线程池隔离离线评测，防止大量黄金集任务占满在线请求线程。
- `KnowledgeEvaluationController` 的 Run 端点：启动异步评测、按 tenant 查询状态；`EvaluationRunPanel.vue` 展示真实汇总指标，不用浏览器本地变量冒充任务进度。
- `RagFeedbackController`：提交前从 KnowledgeBase 读取真实 tenantId，不再固定写入 0；缺少租户归属时拒绝生成反馈记录。
- `V116__rag_rollout_state.sql` + `PostgresRagRolloutRepository`：每个 KB 保存当前灰度与上一可回滚版本；服务重启后状态不丢，upsert 同步更新两代配置。
- `RagRolloutReadinessService` + `ReleaseGateConfiguration`：查询最近已完成 Evaluation Run 的持久化指标，按可配置阈值判定发布门禁；不接受页面传入“已通过”布尔值。
- `ShadowRetrievalService` + V117：Challenger 在独立线程池按采样、预算和超时执行；只持久化 trace、版本、状态、证据 ID 和成本，不保存用户 Query/Chunk 正文。

- [规格总览](../specs/企业级精准知识库RAG-总览.md)
- [架构与数据](../specs/企业级精准知识库RAG-架构与数据.md)
- [质量、安全与迁移](../specs/企业级精准知识库RAG-质量安全与迁移.md)
- [P1 实现计划](../plans/企业级精准知识库RAG_P1文档版本解析与分块.plan.md)
- [开发进度总览](../../开发进度/企业级精准知识库RAG/开发进度总览.md)

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-13 | 建立 P0/P1 Step 1～4 速查地图 | 避免与既有 M3 记忆配置 Feature Map 混写 |
| 2026-08-13 | 补充 P1 Step 5 Contextual Content 与幂等索引任务 | P1 完成 |
| 2026-08-13 | 增加 OpenSearch 官方客户端、环境变量配置与三态健康检查 | P2 Step 1 完成 |
| 2026-08-13 | 增加版本化索引规格、严格 mapping 与 read/write alias 原子切换 | P2 Step 2 完成 |
| 2026-08-13 | 增加 C2 Dense/Sparse 双写与 bulk 部分失败重试 | P2 Step 3 完成 |
| 2026-08-13 | 增加 PG/OpenSearch 差异分类、dry-run 修复与删除传播 | P2 Step 4 完成 |
| 2026-08-13 | 增加管理员索引状态、预检、切换与回滚控制面 | P2 Step 5 完成，P2 收口 |
| 2026-08-13 | 增加规则优先 QueryPlan 与可选 LLM 分析标记 | P3 Step 1 完成 |
| 2026-08-13 | 增加 OpenSearch ACL/版本强制 Pre-filter | P3 Step 2 完成 |
| 2026-08-13 | 增加统一候选协议、多通道降级与加权 RRF | P3 Step 3 完成 |
| 2026-08-13 | 增加统一 Ranking Engine 与严格 LLM 重排协议 | P3 Step 4 完成 |
| 2026-08-13 | 增加专用 Rerank SPI、能力校验与调试时间线 | P3 Step 5 完成，P3 收口 |
| 2026-08-13 | 增加 2～20 条动态证据预算与来源多样性 | P4 Step 1 完成 |
| 2026-08-13 | 增加缺项检测与最多两轮同范围补检索 | P4 Step 2 完成 |
| 2026-08-13 | 增加最终复核、邻居扩展、去重与 token 裁剪 | P4 Step 3 完成 |
| 2026-08-13 | 增加精确 locator Citation 与 Claim 支持校验 | P4 Step 4 完成 |
| 2026-08-13 | 增加分批事实归纳与六态置信状态机 | P4 Step 5 完成，P4 收口 |
| 2026-08-13 | 增加黄金集数据结构与检索指标引擎 | P5 Step 1 完成 |
| 2026-08-13 | 增加 Champion/Challenger 发布门禁与影子检索 seam | P5 Step 2 完成 |
| 2026-08-13 | 增加管理员 RAG 评测 Tab、异步运行状态与 Champion/Challenger 对比卡片 | P5 Step 3 完成 |
| 2026-08-13 | 增加 5/20/50/100 稳定灰度、后端发布前检查、操作者审计与缓存失效回滚 | P5 Step 4 完成 |
| 2026-08-13 | 增加在线反馈待审核队列、低基数 RAG 指标、测试方案/README/旧文档导航 | P5 Step 5 完成，P0–P5 收口 |
| 2026-08-13 | 完成审计后修正索引控制面：快照/路由持久化、未登记目标拦截、首快照 Alias 激活 | P2 Step 5 真实性修复 |
| 2026-08-13 | 接通实际物理索引创建、快照任务、进度恢复、取消、READY 门禁与前端操作 | P2 重建闭环真实性修复 |
| 2026-08-13 | QueryPlan 与真实 Ranking Engine 接入 RagRetrievalService，移除启发式代理伪标 | P3 生产链路修复 1 |
| 2026-08-13 | QueryPlan 控制 Rewrite/HyDE，OpenSearch Exact/Sparse 带可见文档 Pre-filter 接入 | P3 生产链路修复 2 |
