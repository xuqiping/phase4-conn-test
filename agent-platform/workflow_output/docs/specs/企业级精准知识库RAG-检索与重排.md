# 企业级精准知识库 RAG——检索与重排设计

> 上位规格：[企业级精准知识库RAG-总览.md](企业级精准知识库RAG-总览.md)

## 1. QueryPlan

所有请求先生成结构化 QueryPlan，不再只按字符长度决定改写或 HyDE：

```json
{
  "queryType": "POLICY",
  "answerShape": "LIST",
  "normalizedQuery": "2025版差旅制度允许报销哪些费用",
  "entities": ["差旅制度", "报销费用"],
  "filters": {"documentStatus": ["EFFECTIVE"]},
  "retrievalStrategies": ["EXACT", "SPARSE", "DENSE"],
  "requireExhaustive": true,
  "needMultiHop": false
}
```

类型至少包括：`EXACT_MATCH/FACTUAL/PROCEDURAL/POLICY/COMPARISON/AGGREGATION/TEMPORAL/MULTI_HOP/GLOBAL_SUMMARY/CONVERSATIONAL/OUT_OF_SCOPE`。

日期、版本、型号、编号、错误码、引号精确词优先用规则提取；需要语义理解时调用知识库显式选择的 LLM。HyDE 只用于语义型事实问题的低召回补强，不用于型号、编号、精确日期，并且其生成内容永远不能作为回答证据。

## 2. 权限和元数据 Pre-filter

OpenSearch 查询阶段必须按 `tenant/kb/ACL/status/effective_at/expired_at/document_type/department/project/product/language/confidentiality/tags` 过滤，禁止先召回无权限数据再在 Java 内存中删除。上下文组装前和引用输出前再次校验权限、版本状态和内容 Hash。

## 3. 多通道召回

| 通道 | 主要用途 | 初始候选数 |
|---|---|---:|
| Exact | 型号、错误码、条款号、精确短语 | 20 |
| Sparse/BM25 | 关键词、专有词、数字、字段权重 | 80 |
| Child Dense | C2 原文语义召回，默认主通道 | 80 |
| Section Dense | 章节主题定位 | 30 |
| Document Dense | 文档主题定位，不直接证明细节 | 20 |
| Entity | 人、组织、产品、合同主体和关系 | 20 |

Sparse 字段权重、Analyzer 和 RRF 权重全部属于版本化 Pipeline 配置，不散落在 Java 常量中。各通道独立召回后按 `chunk_id` 去重、同文档近邻折叠，再通过加权 RRF 保留约 30～80 条进入重排；数量按 Query 类型动态调整。

## 4. Ranking Engine

统一输出：

```json
{
  "candidateId": "chunk-123",
  "score": 0.92,
  "provider": "LLM",
  "modelConfigId": 23,
  "rankingRunId": "rank-001"
}
```

### LLM 模式

当前采用管理员显式选择的 LLM。约 30 条候选按每批 10 条判断，每批保留 3～5 条，必要时对胜出候选做最终合并排序。输入包含问题、Query 类型、候选 ID、标题路径、版本、生效状态、权威等级和原文；不暴露旧排名分数，避免排序锚定。

输出必须满足 JSON Schema，并包含 `relevance/answerability/specificity/versionValidity/coverageKey/newInformation/duplicateOf`。温度为 0 或模型最低值；候选 ID 必须来自输入；解析失败重试一次，再按配置执行 `FAIL_CLOSED/FALLBACK_RRF/FALLBACK_NO_ANSWER`。

### RERANK 模式

后续专用模型接收 `query + contextual_content` 并输出相关性分数。切换时只修改 `ranking_mode/model_config_id`，Context Builder 不感知供应商差异。

### DISABLED 模式

直接使用 RRF，调试面板必须显示“未经过语义重排”，不得再称为真正的 rerank。

## 5. 多证据问题

列表、流程、比较、聚合问题标记 `requireExhaustive=true`，先定位目录/分类，再按分类生成子查询。排序目标从“最高相关性”调整为“相关性 + 覆盖率 + 非重复性 + 版本正确性”。

| 问题 | 证据建议数 |
|---|---:|
| 单点事实 | 2～4 |
| 条款解释 | 3～6 |
| 流程 | 5～10 |
| 比较 | 每个对象至少 3～5 |
| 列表/汇总 | 8～20 |

多证据流程：第一轮召回 → 提取 `coverageKey` → 每个主题选择最佳证据 → Coverage Verifier 检查缺项 → 针对缺项补检索。普通模式最多补一次，高精度模式最多两次。证据过长时分批生成带引用的结构化事实，再合并、去重和冲突检测；中间事实不得丢失引用。

## 6. 上下文构建

重排命中 C2 后，按文档类型扩展父子和邻居窗口：流程补相邻步骤，条款补主条款/条件/例外，表格补表头和关联行，代码文档补函数或类。不得跨权限、版本或文档边界。

组装顺序：版本冲突检测 → 权限/Hash 复核 → 邻居扩展 → 去重 → 证据多样性 → Token 预算 → 引用编号。单个文档默认最多占一半证据，但精确命中或唯一相关文档可解除限制；比较问题必须保证各方证据都进入上下文。

## 7. 答案、引用与拒答

答案模型只能使用传入证据。每个事实性 Claim 必须绑定 Citation ID；D0/S1 摘要只能导航，不能证明原文不存在的细节。Citation Verifier 检查 Claim 是否被对应原文支持，失败的 Claim 删除或改为不确定表述。

置信度综合：重排 Top1、Top1/Top2 分差、有效证据数、多通道一致性、Exact 命中、Coverage、Citation 完整度、版本冲突和排序稳定性。状态为 `SUPPORTED/PARTIALLY_SUPPORTED/CONFLICTING/INSUFFICIENT_EVIDENCE/OUT_OF_SCOPE/RETRIEVAL_FAILED`。阈值按 Query 类型、模型版本和领域通过黄金集标定，禁止继续全局固定 0.30/0.45。

## 8. 日志关联要求

每轮召回、每批 LLM 重排、补检索和最终生成均写入同一个 `traceId`；批次共享 `rankingRunId`，各自拥有 `modelRequestId`。日志记录配置模式、实际模式、模型配置、候选 Hash、重排前后顺序、覆盖主题、缺失主题、补检索轮次、耗时、Token、费用、降级原因和供应商 Request ID。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| QueryPlan | 把问题整理成检索计划 | 判断它是列表题还是精确编号题 |
| Pre-filter | 搜索前排除不该看的数据 | 只查当前有效且有权限的版本 |
| Coverage | 答案需要覆盖的主题范围 | 十类费用是否都找到证据 |
| Claim | 答案中的一个可验证事实 | “住宿上限为500元” |

