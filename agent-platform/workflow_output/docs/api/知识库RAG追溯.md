# 知识库 RAG 追溯 API

所有接口均要求 `knowledge:manage`，查看动作写入审计日志。响应不包含 Query、Prompt、Chunk、模型输出或审计详情正文。

## 获取 Trace 聚合详情

`GET /api/knowledge/retrieval-logs/traces/{traceId}`

返回 `RagTraceDetailVO`：

- `retrievals`：检索运行、知识库范围、Query Hash、状态和耗时。
- `rankings`：配置模式、实际模式、候选 Hash、降级原因和耗时。
- `modelCalls`：调用用途、模型、供应商、输入/输出 Hash、Token 和状态。
- `usages`：计费日志 ID、模型、Token、费用、积分和状态。
- `audits`：审计日志 ID、操作人、模块、动作、目标和结果；不返回 `detailJson`。

## 反查 Trace

`GET /api/knowledge/retrieval-logs/traces/resolve`

三个条件必须且只能提供一个：

- `modelRequestId`
- `usageLogId`
- `auditLogId`

成功返回 `traceId`；无关联记录返回 404；同时提供多个或全部为空返回 400。
