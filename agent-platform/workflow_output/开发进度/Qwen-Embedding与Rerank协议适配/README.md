# Qwen Embedding 与 Rerank 协议适配开发进度

## 状态

- 分支：`beifen`
- 日期：2026-08-14
- 状态：核心开发与模型独立真实冒烟完成；知识库新文档端到端冒烟被既有数据库漂移阻断

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
- 2026-08-14 收尾复验：后端相关测试 95/95 通过，前端 `src/api/llm.test.ts` 1/1 通过，生产构建成功，后端健康状态 `UP`。

## 已知环境项

- ~~qwen3-rerank 与 qwen3-vl-embedding 未建价表~~ → **已建**（2026-08-14 pricing_rule #11 EMBED / #12 RERANK），真实链路结算正常（EMBED 0.02 分、RERANK 0.19 分/次）。附带修复：rerank usage 只回 `total_tokens` 时回退取值，恒 0 计费问题消除（见 [../企业级精准知识库RAG/开发进度58.md](../企业级精准知识库RAG/开发进度58.md)）。
- 仓库全量测试仍有既有无关失败：MemoryAssetRecall 旧模型配置假设、WebMvc 测试缺 `UserMapper` mock；本次相关测试不受影响。
- 前端全量测试 426/433 通过；7 个既有失败来自测试夹具缺少 Naive UI `DialogProvider`。生产构建通过。
- ~~知识库新文档端到端被 `stored_files` `varchar(16)` 超长阻断~~ → **已修复**（V120 防御拉齐 file_id + V121 放宽 source；真凶是 `source VARCHAR(16)` 装不下 `"KB_PARSE_ARTIFACT"`，此前归因 file_id 有误）。全链路 E2E 已通，详见 [../企业级精准知识库RAG/开发进度58.md](../企业级精准知识库RAG/开发进度58.md)。
