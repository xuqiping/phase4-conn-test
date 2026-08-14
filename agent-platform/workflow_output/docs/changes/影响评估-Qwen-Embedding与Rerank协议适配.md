# 影响评估：Qwen Embedding 与 Rerank 协议适配

日期：2026-08-14

回滚锚点：`e8db47e1`

## 变更目标

修复 Qwen 多模态 Embedding 与平台 OpenAI Embedding 请求格式不兼容的问题，并将已存在但禁用的专用 Rerank 能力接入真实模型调用链。

## 直接引用点

- `llm/provider/LlmProviderInterface`：增加 Rerank 能力契约或独立协议接口。
- `llm/provider/OpenAICompatibleProvider`：保留 OpenAI Embedding，增加可插拔 Embedding/Rerank 请求适配。
- `llm/config/LlmConfig`：按 CHAT/EMBEDDING/RERANK 分离注册表。
- `llm/LlmGateway`：新增 Rerank 统一出口，承接路由、计费、指标和 Trace。
- `llm/service/LlmProviderService`、`llm/controller/LlmController`：新增真实 Rerank 连通测试。
- `knowledge/ranking/ModelRerankProvider`、`RankingConfiguration`、`RankingEngine`：从禁用占位切换为真实委托。
- `knowledge/service/RagRetrievalService`、`RagTraceService`：保持 configured/effective 模式、fallback 与关联 ID 语义。
- `frontend/src/components/settings/ProviderManageTab.vue`、`frontend/src/api/llm.ts`：协议提示、真实测试和结果展示。

## 功能联动

- 文档索引、查询向量、答案缓存向量、个人记忆向量都会复用 Embedding 出口。
- 检索调试、RAG 问答、智能对话和 Agent/工作流共享 Ranking 配置，均可能触发 Rerank。
- Ranking 配置变化会失效答案缓存；不得绕过既有失效机制。
- 显式选择模型、管理员默认模型和“无可用模型明确报错”的解析顺序保持不变。

## 数据与兼容性

- 不修改数据库表结构或已执行 Flyway。
- 维度固定 2048；不重建历史索引。
- OpenAI Embedding 请求/响应保持兼容。
- RERANK 类别不进入 Chat 模型列表，也不作为隐式默认模型。

## 安全与运维

- 外部请求继续使用数据库加密 API Key，禁止导出、打印或回显。
- 日志和 Trace 不得保存 Query、Prompt、documents 或 Chunk 正文。
- 增加 Rerank 成功/失败/耗时记录，并与 retrievalRunId、rankingRunId、modelRequestId 关联。
- 上游 4xx/5xx、超时、响应索引非法、维度不匹配均 fail-closed；只有配置明确允许时才走 RRF fallback。

## 回归范围

- Chat、流式 Chat、OpenAI Embedding、Qwen Embedding。
- LLM/RERANK/DISABLED 三种 Ranking 模式及 fallbackPolicy。
- 管理页供应商增删改查、分类过滤、连通测试。
- 知识库索引、检索调试、RAG 问答、缓存与审计日志。
