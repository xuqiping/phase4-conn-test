# 企业级精准知识库 RAG——架构与数据设计

> 上位规格：[企业级精准知识库RAG-总览.md](企业级精准知识库RAG-总览.md)

## 1. 总体架构与职责边界

### PostgreSQL

保存知识库、ACL、Canonical Document、文档版本、Chunk 元数据、解析/索引任务、模型配置、Pipeline 版本、检索与重排日志、评测和反馈，是唯一权威数据源。OpenSearch 数据丢失后必须能够从 PostgreSQL、原文件和解析产物完整重建。

### OpenSearch/Elasticsearch

承担 BM25、Dense Vector、字段权重、同义词/领域词典、Metadata Pre-filter、高亮、Exact/Sparse/Dense 多通道召回。索引只保存检索需要的副本，不承载不可恢复的业务状态。

### 对象存储

保存原文件、PDF 页面图、OCR/Layout JSON、表格结构、图片区域、标准化 Markdown/JSON 和大体积诊断产物。

### Java 后端模块

| 模块 | 单一职责 |
|---|---|
| Source Registry | 上传、URL、第三方数据源和同步游标 |
| Document Registry | 主文档、版本、生效状态、权威等级和密级 |
| Parse Orchestrator | 解析器选择、OCR、版面与表格恢复 |
| Chunk Factory | 按文档类型分块并生成父子关系 |
| Contextualizer | 为 Chunk 加入文档标题、版本和章节背景 |
| Index Manager | 双写、索引快照、蓝绿切换和回滚 |
| Retrieval Router | QueryPlan、过滤、召回和 RRF |
| Ranking Engine | LLM/RERANK/DISABLED 可插拔重排 |
| Context Builder | 覆盖选择、邻居扩展、去重和 Token 预算 |
| Evidence Verifier | 权限、版本、Hash 和引用校验 |
| Evaluation Center | 黄金集、指标、A/B 和发布门禁 |

现有 `RagRetrievalService` 后续应拆为编排器，不继续承载全部检索细节。

## 2. 文档主数据与版本

一个逻辑文档对应一个 `canonical_document`，每次更新生成新的 `document_version`：

```text
canonical_document
├─ v1 EXPIRED
├─ v2 EXPIRED
└─ v3 EFFECTIVE
```

核心字段：`canonical_document_id/document_version_id/version_number/source_type/source_uri/source_updated_at/effective_at/expired_at/status/supersedes_version_id/content_hash/authority_level/confidentiality_level/owner_id`。

状态为 `DRAFT/PROCESSING/EFFECTIVE/EXPIRED/REVOKED/FAILED`。默认只检索当前时间有效的 `EFFECTIVE` 版本；用户明确问历史版本时才解除过滤。两个有效版本冲突时不得静默合并，必须返回冲突说明。

## 3. 解析与多策略分块

- 普通 Word、Markdown、网页：按标题树恢复章节；C2 建议 300～600 token，Overlap 50～100 token；列表、步骤和完整段落不可从中间切断。
- 合同、制度、法规：按条、款、项切分，提取适用对象、前置条件、例外、禁止事项、关联条款和生效日期。
- FAQ：一个问答对为原子 Chunk，保存原问题、标准问题、同义问法、答案和适用版本。
- Excel/表格：建立 Sheet、表头路径、逻辑行、单元格坐标、合并关系和数据类型；引用定位到 `文件 → Sheet → B12:F12`。
- PDF/扫描件/图片：保存页码、阅读顺序、文本块坐标、OCR、表格/图片区域和跨页关系；图片索引包含 OCR、整体描述、区域描述、图表信息和 Bounding Box。

## 4. 多粒度索引模型

```text
D0 文档级：定位文档主题
S1 章节级：定位章节主题
C2 Child Chunk：事实检索主单元
E3 实体/表格行/视觉区域：精确对象和关系
```

C2 必须建立 Dense 和 Sparse 双索引。用于生成向量的文本：`contextual_content = 文档标题 + 版本 + 标题路径 + 所属背景 + Chunk 原文`。

OpenSearch Chunk 至少包含：

```text
chunk_id, tenant_id, kb_id, canonical_document_id, document_version_id
parent_section_id, chunk_type, title_path, content, contextual_content
dense_vector, keywords, entities, language, acl_tokens
document_status, effective_at, expired_at, authority_level, confidentiality_level
page_number, bbox, content_hash, parser_version, chunker_version
embedding_model_id, embedding_model_version, index_snapshot
```

## 5. 索引版本与蓝绿切换

禁止继续用具体模型名作为逻辑表名。采用 `kb_chunks_read/kb_chunks_write` 别名指向版本化物理索引。升级流程：创建新索引 → 全量重建 → 黄金集评测 → 影子检索 → 灰度 → 切换 read alias → 保留旧索引回滚。不同 embedding 空间禁止混合比较。

## 6. 可插拔重排配置

`ranking_mode` 为 `LLM/RERANK/DISABLED`。配置优先级：知识库覆盖 → 管理员知识库默认 → 无配置明确报错。不得硬编码具体模型，也不得静默替换不可用模型。

配置保存 `ranking_config_id/kb_id/ranking_mode/model_config_id/candidate_limit/final_limit/batch_size/timeout_ms/fallback_policy/high_accuracy_enabled/config_version`。模型能力至少支持 `CHAT/EMBEDDING/RERANK/VISION`。当前 LLM 重排通过统一 `LlmGateway`，未来专用 Rerank 通过统一 Ranking Provider 接入。

## 7. 全链路可观测性

统一标识：`traceId → retrievalRunId → rankingRunId → modelRequestId → providerRequestId`。Query 改写、HyDE、每批 LLM 重排、专用 Rerank、引用验证和答案生成均创建模型调用记录，并以 `RAG_QUERY_REWRITE/RAG_HYDE/RAG_LLM_RANK/RAG_MODEL_RERANK/RAG_CITATION_VERIFY/RAG_ANSWER_GENERATION` 区分用途。

Java MDC 必须携带 `traceId/retrievalRunId/rankingRunId/modelRequestId/userId/kbIds/callPurpose`，并跨线程池、SSE、WebSocket 和异步任务传播。管理后台支持正向和反向追溯。默认不记录完整 Chunk、完整 Prompt、密钥或敏感信息；诊断采样必须有权限、脱敏、保留期限和查看审计。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| Canonical Document | 多个版本共同归属的主文档 | 《差旅制度》是主文档，2025版是一个版本 |
| Child Chunk | 用于精确检索的小原文块 | 某一条报销规则 |
| Contextualization | 给孤立原文补上所属背景 | 加入文档名、章节和版本再做向量 |
| 蓝绿索引 | 新旧索引并存后无损切换 | 新模型评测通过再切 read alias |

