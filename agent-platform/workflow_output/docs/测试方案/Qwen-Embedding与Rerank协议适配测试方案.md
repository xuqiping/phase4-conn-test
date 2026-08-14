# Qwen Embedding 与 Rerank 协议适配测试方案

## 前置条件

- 后端、前端、PostgreSQL、Redis 正常运行。
- 管理员已配置 ACTIVE 的 Qwen Embedding 和 Rerank 供应商及 API Key。
- Embedding 模型维度配置为 2048。

## 管理页测试

1. 设置 → 全局模型供应商 → qwen-embedding → 测试。
   - 预期：提示连接成功、维度 2048、耗时；日志无请求正文。
2. qwen-rerank → 测试。
   - 预期：真实发送固定测试文档；相关文本排在无关文本之前；显示返回条数和耗时。
3. 将错误 Key 或错误端点用于隔离测试环境。
   - 预期：明确失败但不回显 Key、Authorization 或上游完整响应。

## 功能联动测试

1. 使用 qwen-embedding 创建或更新测试知识库并索引唯一文本。
   - 预期：索引完成，向量维度 2048，精准查询命中。
2. 将 Ranking 配置为 `RERANK + qwen3-rerank` 后检索。
   - 预期：configuredMode/effectiveMode 均为 RERANK，产生 rankingRun 和 modelCall。
3. 将 fallbackPolicy 设为 FAIL_CLOSED 并制造上游失败。
   - 预期：请求失败，不伪装为 Rerank 成功。
4. 使用允许 RRF 的配置制造上游失败。
   - 预期：effectiveMode 和 fallback 事件真实反映降级，不记录正文。
5. 切回 LLM 或 DISABLED。
   - 预期：不调用 Rerank 供应商，原排序模式保持可用。

## 回归范围

- OpenAI Embedding 连通测试。
- Chat/流式 Chat。
- 文档索引、查询向量、答案缓存、记忆向量。
- 供应商导入导出、编辑时空 API Key 保留、模型类别过滤。
