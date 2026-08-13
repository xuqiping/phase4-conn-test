# Feature Map · 企业级精准知识库 RAG

> Phase 3 持续维护的粗略功能-代码速查表。当前覆盖 P0、P1 与 P2 Step 1，后续 Chunk 在同一文件追加；文档接近 5000 tokens 时拆分技术清单。
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
| QueryPlan | `backend/src/main/java/com/superprogrammer/knowledge/query/QueryPlanner.java`、`QueryPlan.java` | 规则优先识别精确/比较/流程/列表/语义问题 |
| 召回 Pre-filter | `backend/src/main/java/com/superprogrammer/knowledge/retrieval/RetrievalFilterBuilder.java` | tenant/KB/ACL/status/version 强制前置过滤 |
| 多通道召回 | `RetrievalCandidate.java`、`Retriever.java`、`OpenSearchRetrievers.java`、`RrfFusion.java` | Exact/Sparse/Dense/Entity SPI、降级与加权 RRF |
| Ranking Engine | `backend/src/main/java/com/superprogrammer/knowledge/ranking/` | LLM/DISABLED Provider、候选白名单、严格 JSON 与显式模型 |
| Rerank/时间线 | `ModelRerankProvider.java`、`RagRetrieveVO.java`、`RetrievalDebugPanel.vue` | capability fail-closed 与 QueryPlan/RRF/Ranking 调试协议 |
| 动态证据覆盖 | `backend/src/main/java/com/superprogrammer/knowledge/context/CoverageSelector.java`、`EvidenceBudget.java` | 按问题类型选择 2～20 条并限制单文档挤占 |
| 覆盖补检索 | `CoverageVerifier.java`、`retrieval/RetrievalRouter.java` | 缺项检测、1/2 轮上限、继承原 FilterContext |
| 上下文组装 | `ContextBuilder.java`、`NeighborExpander.java` | PG 复核结果、Hash 去重、授权邻居和 token 硬预算 |
| 引用校验 | `citation/CitationVerifier.java`、`service/internal/CitationChecker.java`、`RagRetrieveVO.java` | locator、权限/版本/Hash、Claim 支持度 |

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

## 数据库迁移速查

- `V103` 起建立文档版本治理；`V107` 增加 owner、来源、权威、密级、标签和有效期；`V108` 为版本行增加解析产物信息；`V109` 为索引任务增加 version/parser/chunker/embedding/pipeline 指纹。
- `knowledge_documents`：一行代表一个主文档，`current_version_id` 是当前有效版书签。
- `knowledge_document_versions`：一行代表一次不可变修订，并保存其文件与解析产物证据。
- `knowledge_nodes`：当前仍使用旧 L0/L2 物理层级，新 D0/S1/C2/E3 协议保存在 metadata，等待后续 OpenSearch 双写迁移。
> 批注：已执行 Flyway 不可修改；任何结构演进必须新增高于 V108 的迁移。

## 相关文档

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
