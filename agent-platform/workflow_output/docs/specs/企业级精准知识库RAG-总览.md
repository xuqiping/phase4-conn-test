# 企业级精准知识库 RAG 设计总览

> 状态：Phase 1 设计已批准
> 日期：2026-08-12
> 方案：路线 B——PostgreSQL 权威数据源 + OpenSearch/Elasticsearch 专业检索 + 可插拔重排
> 当前重排：管理员显式选择的 LLM；未来可切换专用 Rerank 模型
> 约束：不修改既有 14/15/16 速查表，本规格作为新一代系统的设计基线。

## 1. 目标

建设一套以“精准召回、完整覆盖、证据可追溯、权限零泄漏、持续可评测”为核心的企业知识库。系统必须解决现有方案的四个根本问题：

1. L2 原文块不做 Dense Embedding（稠密向量检索），细节问题容易漏召回。
2. 现有所谓 rerank 只是相似度加权，不是真正的 Query-Chunk 语义重排。
3. 固定相似度阈值未经领域评测标定，不能跨模型、语言和知识库复用。
4. 缺少黄金问题集、版本治理、多证据覆盖和发布回归门禁。

### 1.1 用户与核心场景

- 普通用户：用自然语言查询知识库，获得完整、可引用、可定位的答案；证据不足时得到明确拒答。
- 知识库 Owner：上传和更新文档，管理版本、生效期、标签、权限、解析与索引状态。
- 管理员：配置知识库默认的 embedding、LLM 重排或专用 Rerank 模型；查看成本、错误、降级和全链路日志。
- 评测/运维人员：维护黄金问题集，对比 Pipeline，执行影子检索、灰度切换和回滚。

### 1.2 核心功能要求

| 编号 | 要求 |
|---|---|
| RAG-FR-01 | 支持主文档、版本、生效/废止、权威等级和冲突治理 |
| RAG-FR-02 | 支持按文档类型解析、分块，并对 C2 建立 Dense/Sparse 双索引 |
| RAG-FR-03 | 支持权限与元数据 Pre-filter、多通道召回和统一 RRF |
| RAG-FR-04 | 支持 LLM/RERANK/DISABLED 可插拔重排，模型不得硬编码 |
| RAG-FR-05 | 支持 5～10 条及更多证据的覆盖选择、补检索和分批归纳 |
| RAG-FR-06 | 支持页码、条款、Sheet/Cell、视觉区域级精确引用 |
| RAG-FR-07 | 支持黄金集、离线指标、发布门禁和在线反馈回灌 |
| RAG-FR-08 | 支持 Trace、模型调用、审计、Java 后台日志双向关联 |
| RAG-FR-09 | 支持缓存版本化、删除传播、故障降级和蓝绿回滚 |

## 2. 已批准的核心决策

- PostgreSQL 保存业务、权限、文档版本、任务、配置、审计和评测数据，是唯一权威数据源。
- OpenSearch/Elasticsearch 保存可重建的 Dense、Sparse、Metadata、Entity 检索索引。
- 原文件、页面图、OCR、Layout、表格和解析产物进入对象存储。
- C2 Child Chunk 必须同时建立 Dense 向量和 Sparse/BM25 索引。
- 检索采用 Exact + Sparse + Child Dense + Section Dense + Document Dense + Entity 多通道召回。
- 候选先经过 RRF（倒数排名融合——把不同通道的名次合并），再进入可插拔 Ranking Engine。
- Ranking Engine 支持 `LLM`、`RERANK`、`DISABLED`；当前使用 LLM，未来切换专用 Rerank 无需修改检索主链路。
- LLM 模型必须在知识库调用模块中显式选择，遵循“知识库覆盖配置 → 管理员知识库默认配置 → 无配置明确报错”，不得硬编码模型 ID，也不得偷选供应商列表第一项。
- 多证据问题使用动态 Top-K、主题覆盖、分批归纳和缺项补检索，不使用固定 Top 5/8。
- LLM 重排、专用 Rerank、Query 改写、HyDE、答案生成必须与检索日志、模型调用日志、审计日志和 Java MDC 日志通过同一组 Trace ID 关联。

## 3. 文档导航

| 文档 | 内容 |
|---|---|
| [企业级精准知识库RAG-架构与数据.md](企业级精准知识库RAG-架构与数据.md) | 总体架构、文档版本、解析分块、多索引、模型配置、可观测性 |
| [企业级精准知识库RAG-检索与重排.md](企业级精准知识库RAG-检索与重排.md) | QueryPlan、多通道召回、RRF、LLM/Rerank、多证据、上下文与拒答 |
| [企业级精准知识库RAG-质量安全与迁移.md](企业级精准知识库RAG-质量安全与迁移.md) | 评测、反馈、安全、缓存、性能、降级和双轨迁移 |
| [企业级精准知识库RAG-旧文档差异清单.md](企业级精准知识库RAG-旧文档差异清单.md) | 旧 14/15/16 文档中未来需要调整的具体内容；旧文件本次不修改 |

## 4. 首期范围

首期必须交付：Canonical Document 与文档版本治理；结构化解析和 C2 Child Chunk Dense/Sparse 双索引；OpenSearch 双写、对账、影子检索和灰度切换；Query 类型识别、Metadata/ACL Pre-filter、多通道召回和 RRF；LLM 可插拔重排；动态 Top-K、多证据覆盖、引用校验和校准拒答；黄金问题集和发布门禁；缓存版本化、删除传播和故障降级。

首期不做：全量 GraphRAG、自动训练自有 Reranker、无人工审核的在线自学习、所有图片默认进行昂贵的区域级视觉理解。上述能力保留扩展接口，但不阻塞首期。

## 5. 总体数据流

```text
数据源 → 接入同步 → 结构化解析/OCR → 文档版本治理
     → 多策略分块/上下文化 → PostgreSQL + 对象存储 + OpenSearch
     → QueryPlan → 权限/版本 Pre-filter → 多通道召回 → RRF
     → LLM 或专用 Rerank → 覆盖选择/邻居扩展 → 引用校验
     → Grounded Answer → 置信拒答 → 评测/反馈/审计
```

## 6. 总体验收标准

- Recall@20 ≥ 92%，nDCG@10 ≥ 80%。
- 精确编号召回率 ≥ 98%，正确版本使用率 ≥ 98%。
- Citation Correctness ≥ 95%，多证据 Coverage Recall ≥ 90%。
- 无依据事实率 ≤ 2%，权限泄漏率必须为 0。
- 无 LLM 的检索链路 P95 ≤ 1 秒；单轮 LLM 重排 P95 目标 ≤ 4 秒。
- 任一结果能从用户请求追溯到检索、重排、模型调用、供应商 Request ID、Java 后台日志和当时的配置版本。

## 7. 权威参考基线

- Azure AI Search：Hybrid Search、RRF、Semantic Ranker。
- Elastic：BM25、Vector、RRF、多 Retriever。
- Anthropic Contextual Retrieval：为 Chunk 补充文档和章节背景后再建立检索索引。
- Microsoft GraphRAG：Local、Global、DRIFT 按问题类型路由，而非所有问题强制走图谱。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| Dense Retrieval | 按语义相似度检索 | “差旅住宿标准”能找到“出差酒店限额” |
| Sparse/BM25 | 按关键词和词频检索 | 精确找到错误码、型号、条款号 |
| Rerank | 对召回候选再次精排 | 判断哪段原文真正回答问题 |
| RRF | 按多个通道的排名合并 | BM25 第1和向量第3的结果综合靠前 |
| Grounded Answer | 只根据给定证据回答 | 每个事实都能落到具体引用 |
