# Qwen Embedding 与 Rerank 协议适配设计

日期：2026-08-14

状态：已获用户批准，待实现
变更原因：现有平台仅实现 OpenAI 文本 Embedding 协议，且专用 Rerank Provider 被禁用，导致已配置的 `qwen3-vl-embedding` 返回 HTTP 400、`qwen3-rerank` 无法进入真实调用链。

## 1. 目标与边界

- 保留现有 OpenAI/Claude/视频/生图供应商行为。
- 为 Qwen 多模态 Embedding 协议增加文本向量化适配。
- 为 `RERANK` 类别增加真实调用、连通测试、计费、Trace 和 Java 日志。
- 运行时代码不硬编码具体模型 ID；模型仍由管理员配置并显式选择。
- 本次向量维度固定为 2048，与现有 PostgreSQL `halfvec(2048)`、HNSW 索引及历史向量数据兼容。
- 本次不支持在知识库中切换到 1536/1024 等其他维度；如需切换，必须另行设计向量表、索引和全量重建方案。

## 2. 协议选择

采用“协议适配器 + 兼容识别”方案：

1. 供应商 `config` 可声明适配器类型，管理页保存时写入该配置。
2. 既有配置 `config=null` 时，可由类别和端点协议特征识别 Qwen 多模态 Embedding；不依赖模型名称。
3. 普通 `EMBEDDING` 供应商继续发送 OpenAI 格式，不改变既有行为。
4. `RERANK` 供应商走独立 Rerank 注册表和网关出口，不进入 Chat 或 Embedding 路由。

不采用：仅按模型名称硬编码；仅按端点字符串长期判断；将 Rerank 冒充 Chat 调用。

## 3. Embedding 数据流

知识库/记忆/连通测试调用 `LlmGateway.embed(text, selectedModel)` 后：

1. 按 `EMBEDDING` 类别和显式模型找到供应商。
2. Qwen 多模态适配器发送：

```json
{
  "model": "由管理员选择的模型",
  "input": {"contents": [{"text": "待向量化文本"}]},
  "parameters": {"enable_fusion": true, "dimension": 2048}
}
```

3. 解析 `output.embeddings[0].embedding`；普通 OpenAI 响应继续解析 `data[0].embedding`。
4. 返回前强制校验维度为 2048；不匹配时明确失败，不写入向量表。
5. usage 缺失时沿用 TokenEstimator 估算，不影响正常响应。

管理页显示“当前知识库存储维度：2048（固定）”，避免管理员误以为可直接切换其他维度。

## 4. Rerank 数据流

`RankingEngine` 在配置模式为 `RERANK` 时调用真实 ModelRerankProvider：

1. 通过 `LlmGateway.rerank` 按 `RERANK` 类别和显式模型路由。
2. 将候选的标题和正文组合为 `documents`，禁止写入日志。
3. 请求包含 `model/query/documents/top_n/instruct`；`top_n` 为本批候选数，最终证据数仍由 RankingConfig 的 `finalLimit` 控制。
4. 根据上游返回的 `index/relevance_score` 映射回 candidateId，拒绝越界、重复或未知索引。
5. 返回未覆盖全部候选时，仅采用上游明确返回的候选；是否退回 RRF 由既有 `fallbackPolicy` 决定，禁止静默伪装为 Rerank 成功。

默认 instruct：`Given a web search query, retrieve relevant passages that answer the query.`。允许以后放入供应商 `config` 覆盖，本次不新增知识库级字段。

## 5. 可观测性与计费

- Embedding 与 Rerank 均通过 `LlmGateway` 形成统一模型调用出口。
- Rerank 使用独立调用用途 `RERANK`，记录 provider/model/result/latency。
- 复用当前 RAG `traceId/retrievalRunId/rankingRunId/modelRequestId` 上下文。
- `rag_ranking_runs` 分开记录 configuredMode 与 effectiveMode。
- Java 日志只记录关联 ID、模型、供应商、候选数量、耗时和安全错误摘要。
- 禁止记录 API Key、Authorization、Query、Prompt、documents、Chunk 正文或完整上游响应。
- 新增 Rerank usage kind；上游无 token usage 时记录估算或零用量，不得因计费失败回滚成功的模型响应。

## 6. 管理员体验

- `EMBEDDING` 测试使用真实文本请求，并显示返回维度和耗时。
- `RERANK` 测试使用固定的非敏感示例 query/documents，显示返回条数、首位索引和耗时。
- 测试失败返回安全摘要与 Trace ID，不回显上游响应正文或密钥。
- RAG 默认配置仍必须由管理员显式选择 `RERANK + 模型`；仅注册供应商不会自动改变默认重排模式。

## 7. 测试与验收

- 单元测试先失败后实现：Qwen Embedding 请求体、响应解析、2048 校验；Rerank 请求体、索引映射、异常响应和类别隔离。
- 回归：OpenAI Embedding、Chat、LLM 重排、DISABLED、fallbackPolicy、计费和 Trace 原有测试。
- 真实冒烟：管理页分别测试 qwen-embedding/qwen-rerank；使用一个测试知识库执行真实索引与 RERANK 检索；核对页面、Trace、模型调用记录和 Java 日志。

## 8. 回滚

- 代码回滚锚点：`e8db47e1`。
- 本次不修改向量表结构；回滚代码即可恢复旧调用行为。
- 若新增供应商 `config` 内容，旧代码会忽略该字段，不影响回滚启动。
