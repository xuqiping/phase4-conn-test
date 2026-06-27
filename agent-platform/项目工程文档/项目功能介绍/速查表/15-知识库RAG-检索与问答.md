# 15 - 知识库 RAG - 检索与问答

## 功能简介
混合检索（向量 + L1 元数据 lexical fallback）、可见文档集过滤、引用证据校验、SSE 流式问答（带引用）。模式解析(mode/scope)由 RagModeResolver/RagScopeResolver 决定。

## 后端 (backend) — `knowledge` 包
- 问答：[KnowledgeAskController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeAskController.java) — `POST /api/knowledge/ask`（SSE 流式）
- 检索：[KnowledgeRetrieveController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeRetrieveController.java) — `POST /api/knowledge/retrieve`
- 服务：`knowledge/service/`
  - [RagRetrievalService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/RagRetrievalService.java) — 检索核心
  - RagModeResolver、RagScopeResolver、VisibilitySetService（可见集）
  - 内部 CitationChecker（引用校验）
- DTO：AskRequest、RagRetrieveRequest、RagRetrieveVO、EvidenceResult、CacheCandidateRow、CachedPayload、RagQueryRow

## 前端 (frontend)
- 组件：[knowledge/RagAskPanel.vue](../../frontend/src/components/knowledge/RagAskPanel.vue)（问答）、[knowledge/RetrievalDebugPanel.vue](../../frontend/src/components/knowledge/RetrievalDebugPanel.vue)（调试）
- API：[knowledge.ts](../../frontend/src/api/knowledge.ts)
- 工作流检索节点：[workflow/RetrievalNode.vue](../../frontend/src/components/workflow/RetrievalNode.vue)

## Sidecar
工作流中 Retrieval 节点经回调链路触发后端检索（`node_runtime.resolve_source` → `RETRIEVAL`），见 [12-Runtime-Sidecar执行](12-Runtime-Sidecar执行.md)。

## ⚠️ 调试注意
`rag_retrieval_logs.l2_lexical_fallback` NOT NULL，短路前必设 false，否则 trace 静默丢。详见 [17-检索审计日志](17-检索审计日志.md)。

## 数据表
`knowledge_embeddings`(检索源)、答案缓存见 [16-记忆与缓存对账](16-知识库RAG-记忆与缓存对账.md)


## 待增修改功能
1. 命中率太低了，再看看怎么优化 → **RAG 召回升级**，进度/方案/下一步全在 [15-知识库RAG-检索与问答-优化进度](15-知识库RAG-检索与问答-优化进度.md)（Phase1 多路扩展+软拒答 ✅已落地验证，Phase2 jieba-BM25 / Phase3 L1向量 ⏳待做）