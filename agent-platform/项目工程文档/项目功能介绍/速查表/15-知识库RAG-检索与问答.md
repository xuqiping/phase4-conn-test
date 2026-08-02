# 15 - 知识库 RAG - 检索与问答

## 功能简介
多通道混合召回（L0 章节摘要向量 + L1 文档元数据向量 + jieba-BM25 词法，RRF 跨通道融合 + 启发式 rerank）、可见文档集过滤、软拒答 hard/soft 双阈、引用证据校验、SSE 流式问答（带引用）。模式解析(mode/scope)由 RagModeResolver/RagScopeResolver 决定。

## 后端 (backend) — `knowledge` 包
- 问答：[KnowledgeAskController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAskController.java) — `POST /api/knowledge/ask`（SSE 流式）
- 检索：[KnowledgeRetrieveController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeRetrieveController.java) — `POST /api/knowledge/retrieve`
- 服务：`knowledge/service/`
  - [RagRetrievalService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java) — 检索核心（`retrieve` 调试用 / `retrieveEvidence` 多 KB 证据，Chat·Agent·工作流·/ask 注入用）
  - [QueryExpansionService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/QueryExpansionService.java) — query 多路扩展（短→改写+HyDE / 长→切块多路 / 关→单 query），4 条检索路径同调
  - [JiebaTokenizer.java](../../backend/src/main/java/com/superprogrammer/knowledge/util/JiebaTokenizer.java) — jieba 中文分词（BM25 词法通道用）
  - [L1EmbedText.java](../../backend/src/main/java/com/superprogrammer/knowledge/util/L1EmbedText.java) — L1 文档向量文本拼装（writer/worker/tx 共用防漂移）
  - RagModeResolver、RagScopeResolver、VisibilitySetService（可见集）
  - 内部 CitationChecker（引用校验）、RrfFusion（跨通道 RRF 融合纯函数）
- 配置：[RagRecallProperties.java](../../backend/src/main/java/com/superprogrammer/knowledge/config/RagRecallProperties.java) `rag.recall.*`（expansion/hyde/rrf/abstain/rerank 旋钮）
- DTO：AskRequest、RagRetrieveRequest（`generateAnswer` 默认 false）、RagRetrieveVO、EvidenceResult、CacheCandidateRow、CachedPayload、RagQueryRow

## 前端 (frontend)
- 组件：[knowledge/RagAskPanel.vue](../../frontend/src/components/knowledge/RagAskPanel.vue)（问答）、[knowledge/RetrievalDebugPanel.vue](../../frontend/src/components/knowledge/RetrievalDebugPanel.vue)（调试，带「生成答案」勾选 + 扩展态只读徽章）
- 设置：[settings/RagRecallSettingsTab.vue](../../frontend/src/components/settings/RagRecallSettingsTab.vue)（设置页「RAG/召回」tab，扩展开关 + 切块阈值，DB 持久化不重启）
- API：[knowledge.ts](../../frontend/src/api/knowledge.ts)、[system.ts](../../frontend/src/api/system.ts)（`getRagRecallSettings`/`updateRagRecallSettings`）
- 工作流检索节点：[workflow/RetrievalNode.vue](../../frontend/src/components/workflow/RetrievalNode.vue)

## Sidecar
工作流中 Retrieval 节点经回调链路触发后端检索（`node_runtime.resolve_source` → `RETRIEVAL`），见 [12-Runtime-Sidecar执行](12-Runtime-Sidecar执行.md)。

## ⚠️ 调试注意
- `rag_retrieval_logs.l2_lexical_fallback` NOT NULL，短路前必设 false，否则 trace 静默丢。详见 [17-检索审计日志](17-检索审计日志.md)。
- **软拒答双阈**（`rag.recall.abstain`）：`bestSim<hard(0.30)` → LOW_CONFIDENCE 拒答；`[hard,soft=0.45)` 灰区照回答但 `lowConfidence=true` 且不写缓存；`≥soft` SUPPORTED。`bestSim=max(父L0 sim, doc L1 sim)`。
- **query 扩展 = 全局运行时开关**（设置页「RAG/召回」，DB 键 `rag.recall.expansion.{enabled,threshold}`，默认开/200）。4 条检索路径（/retrieve、/ask、Chat、Agent·工作流）同读 → 调试与真实一致。关→单 query；开+≤阈值→改写+HyDE；开+>阈值→切块多路（段落/句子切 ≤阈值字、取前 8 块各 embed，不调改写 LLM，治万字多主题不丢内容）。
- **`/retrieve` 默认不生成答案**（`RagRetrieveRequest.generateAnswer` 默认 false）：只返候选 L0/证据 L2/token 预算，秒级；勾「生成答案」才调 LLM 出带引用答案（10s+），此时才写答案缓存。要流式答案去 `/ask`。
- `rag_retrieval_logs.candidates_l1`（V37 加列）记 L1 文档向量通道命中，可空（短路路径未算 L1 即 null）。

## 数据表
`knowledge_embeddings`(L0 检索源)、`knowledge_doc_embeddings_doubao`(L1 文档向量，V36)、`rag_retrieval_logs`(审计，含 `candidates_l0`/`candidates_l1`/`l2_lexical_fallback`/`token_budget`)、答案缓存见 [16-记忆与缓存对账](16-知识库RAG-记忆与缓存对账.md)。扩展开关存 `system_settings`（键 `rag.recall.expansion.*`）。


## 已落地演进
1. **RAG 召回升级 ✅**（治"换说法召回不到"）：多路扩展（QueryExpansionService）+ 软拒答双阈 + jieba-BM25 词法兜底（V35）+ L1 文档向量通道·RRF 跨通道融合（V36）+ 清死代码 + L1 trace 列（V37）。
2. **检索调试不生成答案 ✅**：`/retrieve` 默认纯检索（`generateAnswer` 默认 false），加「生成答案」勾选才调 LLM；答案生成归 `/ask` SSE 流式。
3. **query 扩展统一运行时开关 ✅**：设置页「RAG/召回」tab 控制（DB 持久化，4 路同读，调试=真实）；长输入走切块多路召回。

> 迁移：V35（jieba BM25）/ V36（L1 文档向量 + index_jobs.document_id）/ V37（rag_retrieval_logs.candidates_l1）。跑法：H2 跑不了 halfvec/tsvector，mapper 测走 IT profile 真 PG。