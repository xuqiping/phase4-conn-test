# 企业级精准知识库 RAG——质量、安全与迁移

> 上位规格：[企业级精准知识库RAG-总览.md](企业级精准知识库RAG-总览.md)

## 1. 黄金集与指标

每条黄金样本保存问题类型、正确文档版本、相关 Chunk、禁止候选、必答主题、是否可回答、预期 Claim 与证据映射。样本覆盖精确编号、同义改写、否定/例外、新旧版本、单点事实、5～10 条多证据、比较、多跳、表格/PDF/图片、无答案、无权限和 Hard Negative（很像但实际不相关的困难负样本）。

指标包括：`Recall@5/20、Precision@K、MRR、nDCG@10、Exact Match Recall、Version Correctness、Coverage Recall、Duplicate Rate、Claim Correctness、Citation Correctness/Completeness、Faithfulness、Conflict Detection、Abstention Precision/Recall`。

首期门槛：Recall@20≥92%、nDCG@10≥80%、精确编号≥98%、正确版本≥98%、引用正确≥95%、多证据覆盖≥90%、无依据事实≤2%、权限泄漏=0。

任何 embedding、chunker、Analyzer、RRF、Ranking 模型/Prompt、拒答阈值、上下文或答案 Prompt 变更都必须跑回归。采用 Champion/Challenger 并行评测，未达门槛禁止切换生产索引。

## 2. 在线反馈闭环

用户反馈包括有帮助、无帮助、引用错误、回答不完整、内容过期、应使用其他文档。系统采集零召回、连续改写、引用点击、补检索次数、降级次数和高分误判。

反馈流程必须是：收集 → 聚类 → 人工确认 → 转成黄金样本/Hard Negative → 离线评测 → 发布新 Pipeline。禁止未经审核直接改变生产排序。

## 3. 权限与内容安全

Chunk 继承 tenant、KB、文档、部门、用户/组和密级。执行检索前过滤、重排前校验、上下文前校验、引用前校验。权限变化后同步更新 ACL 索引并失效答案/重排缓存；索引尚未同步时，以 PostgreSQL 二次校验为最终裁决。

文档内容一律视为不可信证据，不能成为系统指令。检测“忽略系统指令”、索取密钥、调用工具、跨用户取数、隐藏文字和恶意 OCR。高风险文档隔离或人工审核，并记录来源和处理结果。

删除文档必须传播到文档版本、Chunk、Dense/Sparse、实体/图谱、答案缓存、重排缓存、摘要和解析产物，并定义可监控的删除 SLA。

## 4. 缓存一致性

- 权限缓存 Key：`tenantId/userId/permissionVersion`，权限变化立即递增版本。
- 重排缓存 Key：`queryHash/candidateIdsAndHashes/permissionSignature/rankingMode/rankingModelId/modelConfigVersion/rankingPromptVersion/pipelineVersion`。
- 答案缓存校验：`queryEmbeddingModelId/version/knowledgeSnapshot/permissionSignature/evidenceIdsAndHashes/rankingMode/model/version/answerModel/version/rankingPromptVersion/answerPromptVersion/pipelineVersion`。

修复现有缓存读取未按 `key_embedding_model` 过滤的问题。缓存命中仍须复核权限、文档状态、证据 Hash 和撤销状态。

## 5. 性能与成本

目标 P95：规则分析≤50ms、权限过滤≤100ms、多通道召回≤500ms、RRF≤100ms、上下文构建≤200ms、无 LLM 检索链路≤1秒；单轮 LLM 重排≤4秒，多批次≤8秒，未来专用 Rerank≤1秒。

每个知识库可配置最大召回数、重排候选、批次数、补检索轮次、输入 Token、单请求成本、超时和高精度模式。LLM 调用统一经过 `LlmGateway`，必须关联用户和计费上下文。

## 6. 故障降级

| 故障 | 可配置行为 |
|---|---|
| Dense 失败 | Sparse + Exact |
| Sparse 失败 | Dense |
| Query LLM 失败 | 规则分析 + 原 Query |
| LLM 重排失败 | RRF、明确失败或不回答 |
| 专用 Rerank 失败 | LLM、RRF 或明确失败 |
| 补检索失败 | 返回已覆盖部分并标记不完整 |
| 答案生成失败 | 返回证据列表 |
| Citation 校验失败 | 删除无依据 Claim |

任何降级都记录 `configuredMode/effectiveMode/fallbackReason/traceId/retrievalRunId/rankingRunId`，不得静默发生。

## 7. 双轨迁移

1. 治理与接口：Pipeline 版本、模型能力、统一 Ranking Provider、Trace、黄金集、缓存模型隔离；继续用旧 PG 检索。
2. 解析与数据：Canonical Document、文档版本、C2 Chunk、页码/表格/坐标和 contextual content；旧 L0/L1/L2 保留。
3. OpenSearch 双写：对账文档数、Chunk 数、Hash、ACL、失败率和延迟。
4. 影子检索：旧链路服务用户，新链路只产生日志和评测结果。
5. 灰度切换：按 KB 或用户从 5%→20%→50%→100%，保留旧链路短期回滚；稳定后停止旧向量表写入但不立即删除历史数据。

## 8. 测试策略

- 单元测试：Query 分类、过滤构造、RRF、覆盖选择、缓存 Key、阈值状态机。
- 集成测试：PG/OpenSearch 双写、ACL、版本过滤、索引别名切换和对账。
- 模型契约测试：LLM JSON Schema、无效候选、超时、重试、计费和 Trace。
- E2E：上传/更新/撤销 → 索引 → 查询 → 重排 → 引用 → 删除传播。
- 安全测试：跨用户、权限竞态、缓存污染、Prompt Injection 和日志泄密。
- 性能测试：单点、多证据、30候选多批 LLM、并发检索和 OpenSearch 故障。
- 人工测试：模型选择、检索时间线、引用定位、影子对比和灰度回滚。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 黄金集 | 已知正确答案和证据的考试题 | 发布前固定跑一遍 |
| Hard Negative | 看起来很像但不正确的候选 | 旧版制度包含相同关键词 |
| Champion/Challenger | 生产方案和候选方案对打 | 比较新旧 embedding 模型 |
| 影子检索 | 新链路后台运行但不影响用户 | 只记录新结果用于对比 |

