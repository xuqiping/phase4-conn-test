# 企业级精准知识库 RAG——旧文档差异与应修改项

> 本文件只记录差异，不修改旧文件。
> 旧文件继续作为“现有系统实现速查和历史进度”，新一代目标设计以 [企业级精准知识库RAG-总览.md](企业级精准知识库RAG-总览.md) 为准。

## 1. `14-知识库RAG-更新进度.md`

旧文件：`项目工程文档/项目功能介绍/速查表/14-知识库RAG-更新进度.md`。

未来若维护旧文件，应调整：

1. 定位为“旧版 RAG 图片/文件接入历史”，不再承担新一代 RAG 总体进度。
2. 增加本规格导航，说明 L0/L1/L2、旧向量表和引用回显只是当前基线。
3. 后续进度进入独立开发进度目录，按 OpenSearch、版本治理、重排、评测拆分。
4. 视觉知识补充 OCR、页码、Layout、表格坐标和区域证据，不只记录整图描述与原件回显。
5. “P1/P2/P3 完成”只表示旧方案阶段完成，不能理解为精准召回系统已经完成。

## 2. `14-知识库RAG-基础.md`

旧文件：`项目工程文档/项目功能介绍/速查表/14-知识库RAG-基础.md`。

| 旧设计 | 新设计 |
|---|---|
| `_doubao` 表名绑定模型 | 模型无关业务数据 + 版本化 OpenSearch 物理索引和读写别名 |
| L0/L1 为主要 Dense 入口 | C2 Child Chunk Dense 为事实召回主入口，D0/S1 做导航增强 |
| 通用字符/段落切块 | 普通文档、合同、FAQ、表格、PDF、图片分别分块 |
| 文档记录代表当前内容 | Canonical Document + Document Version + 生效/废止/替代关系 |
| 图片主要为视觉描述和原件回显 | OCR + Layout + 区域描述 + bbox + 页码/表格坐标证据 |

还需增加 OpenSearch、对象存储解析产物、索引快照、蓝绿重建、ACL 同步和删除传播的速查内容。

## 3. `15-知识库RAG-检索与问答.md`

旧文件：`项目工程文档/项目功能介绍/速查表/15-知识库RAG-检索与问答.md`。

1. “启发式 rerank”必须标记为旧版排序加权，不得称为真正 Rerank；现有 `rerankModel` 尚未进入真实链路。
2. 新链路采用 `LLM/RERANK/DISABLED`；当前 LLM 显式选模型，未来专用 Rerank 只切配置。
3. Query Expansion 改为 QueryPlan 分类后选择 Exact、改写、HyDE、子问题、时间过滤或全局路径。
4. HyDE 不再对普通短 Query 全局使用；型号、编号、日期等精确问题禁用。
5. 所有召回通道使用统一候选协议参与 RRF，不再把 BM25 主要当 union/boost。
6. 废弃“L2 不嵌入”，C2 Child Chunk 同时建立 Dense/Sparse 索引。
7. 废弃固定 `hard=0.30/soft=0.45`，阈值按 Query 类型、领域、模型和黄金集标定。
8. 固定 Top-K 改为动态证据预算；多证据问题增加 coverageKey、补检索和分批归纳。
9. Metadata 过滤扩展到版本、生效时间、部门、项目、产品、语言、密级和标签 Pre-filter。
10. 引用扩展到页码、条款号、Sheet/Cell 和视觉 bbox。
11. 调试面板增加 QueryPlan、各通道排名、RRF、重排前后、覆盖主题、补检索、模型调用、费用和 Trace 时间线。

## 4. `16-知识库RAG-记忆与缓存对账.md`

旧文件：`项目工程文档/项目功能介绍/速查表/16-知识库RAG-记忆与缓存对账.md`。

1. 当前答案缓存虽验证权限签名和证据 Hash，但读取未按 `key_embedding_model` 过滤，模型切换后存在跨向量空间比较风险。
2. 答案缓存加入知识快照、embedding/ranking/answer 模型版本、Prompt 版本和 Pipeline 版本。
3. 新增重排缓存，并加入候选 ID+Hash、权限签名、模式和模型配置版本。
4. 权限变化、文档撤销、版本切换、删除和索引切换主动失效缓存，不能只依赖 TTL。
5. 对账扩展到 PostgreSQL↔OpenSearch 文档/Chunk/ACL/Hash、索引别名、对象存储产物和删除传播 SLA。
6. `rag_memory_facts` 继续明确为未启用占位能力；没有生产者、冲突治理和生命周期规格前不纳入首期。

## 5. 代码层已知偏差

后续 Phase 2 必须纳入：

- `RagRetrievalService` 已承认 rerank 使用父 L0 cosine 代理。
- `rerankWithBoost()` 实际为 `parentL0Sim + bmBoost + l1Boost`。
- 拒答使用 `max(parentL0Sim, docL1Sim)` 和固定 0.30/0.45。
- `KnowledgeNodeWriter.splitL2()` 主要按段落累积和字符硬切，没有 overlap、结构感知或语义边界。
- `RagAnswerCacheMapper.searchCandidates()` 未按 embedding 模型过滤。
- `KnowledgeBase.rerankModel` 和前端字段目前没有驱动真正的重排调用。
- `_doubao` 表名造成模型/索引耦合。

以上是现状基线，不要求现在直接修改代码；实施必须先进入 Phase 2 拆计划。

## 6. 文档生效规则

```text
本规格（目标设计）
> 后续批准的 Phase 2 计划
> 旧 14/15/16 速查表（现状与历史说明）
```

旧文档本次保留原样，避免历史进度和当前实现信息丢失。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 现状基线 | 系统现在真实做到的程度 | 启发式加权仍是当前行为 |
| 目标设计 | 后续要建设成的状态 | LLM/专用 Rerank 可切换 |
| 差异清单 | 告诉后续开发哪些旧说法过时 | 固定阈值应被评测标定替代 |
