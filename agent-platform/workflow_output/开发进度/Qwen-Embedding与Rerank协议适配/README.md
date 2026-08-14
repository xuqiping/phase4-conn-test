# Qwen Embedding 与 Rerank 协议适配开发进度

## 状态

- 分支：`beifen`
- 日期：2026-08-14
- 状态：开发与真实冒烟完成

## 已完成

- Qwen 多模态 Embedding 请求/响应适配，固定 2048 维并保留普通 OpenAI Embedding。
- 专用 Rerank DTO、Provider、独立注册表与 `LlmGateway.rerank`。
- RAG Trace、Java 日志、指标、计费关联；正文与密钥不入日志。
- `ModelRerankProvider` 接入真实 Gateway；管理员专用 Rerank 测试接口。
- 设置页真实 Embedding/Rerank 测试与 2048 维提示。
- V119 扩展 RERANK 调用明细/价表约束，后台价表支持重排输入 token 定价。

## 验证证据

- 后端相关组合测试：Embedding/Gateway/Config/Ranking/诊断/计费均通过。
- 前端新增 API 单测通过；`vue-tsc + vite build` 通过。
- 真实 `qwen3-vl-embedding`：返回 2048 维。
- 真实 `qwen3-rerank`：返回 Top 2，且相关文档索引为 0、2，无关量子计算索引 1 被降权。
- 浏览器设置页：两类测试均出现成功提示，Embedding 编辑弹窗显示固定 2048 维说明。
- 日志正文扫描：测试 Query、documents 与样例文本命中数均为 0。

## 已知环境项

- 当前 qwen3-rerank 与 qwen3-vl-embedding 尚未建立价表，因此模型调用成功，但 `llm_usage_logs` 会按既有计费降级规则记录“未配置价表”的 FAILED 明细且不扣积分。管理员建立 RERANK/EMBED 价表后即可正常结算。
- 仓库全量测试仍有既有无关失败：MemoryAssetRecall 旧模型配置假设、WebMvc 测试缺 `UserMapper` mock；本次相关测试不受影响。
- 前端全量测试 426/433 通过；7 个既有失败来自测试夹具缺少 Naive UI `DialogProvider`。生产构建通过。
