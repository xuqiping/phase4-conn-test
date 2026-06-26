# 14 - 知识库 RAG - 基础

## 功能简介
知识库(KB) CRUD、文档上传/解析/分块向量化(Embedding)、文档节点(chunk)查看、知识库可见性权限。属 RAG v6 精简版。

## 后端 (backend) — `knowledge` 包
- 知识库：[KnowledgeBaseController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeBaseController.java) — `GET/POST /api/knowledge/bases` `GET/PUT/DELETE /{id}`
- 文档：[KnowledgeDocumentController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeDocumentController.java) — `POST /api/knowledge/documents/upload` `GET` `GET/DELETE /{id}`
- 节点：[KnowledgeNodeController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeNodeController.java) — `GET /api/knowledge/documents/{docId}/nodes`
- 权限：[KnowledgePermissionController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgePermissionController.java) — `GET POST /api/knowledge/permissions` `DELETE /{id}`
- 服务：`knowledge/service/`
  - KnowledgeBaseService、KnowledgeDocumentService、KnowledgeNodeService、KnowledgeNodeWriter、KnowledgePermissionService、VisibilitySetService
  - 内部：[knowledge/service/internal/](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/) ExtractedDocument、Section、L1Metadata、VisibleDocSet、IndexJobTxService、CitationChecker、BatchLlmResult
- 索引：DocumentParserService、IndexJobWorker（异步向量化）、配置 KnowledgeTaskExecutorConfig
  - **轮询节拍**：[IndexJobWorker.poll()](../../backend/src/main/java/com/superprogrammer/knowledge/service/IndexJobWorker.java) `@Scheduled(fixedDelayString="${knowledge.index.poll-ms:5000}")` 每 5s 认领一批 PENDING/RUNNING(过期) job → 丢 `knowledgeTaskExecutor` 异步 embed。队列空也照发认领 SQL（日志刷 `SELECT ... knowledge_index_jobs ... FOR UPDATE SKIP LOCKED` `Total: 0`）→ **正常背景噪声，非故障**；想静音调高 `knowledge.index.poll-ms` 或日志级别。与记忆模块（`memoryBackfill`、`mem-task-` 线程）完全独立，别混。
- 事件：`knowledge/event/` DocumentUploadedEvent、DocumentParseListener、VisibilityInvalidationEvent、VisibilityInvalidationListener
- 实体：`knowledge/entity/` KnowledgeBase、KnowledgeDocument、KnowledgeEmbedding、KnowledgeIndexJob、KnowledgeNode、KnowledgePermission
- Mapper：`knowledge/mapper/`（含 VisibilityQueryMapper、RagRetrievalQueryMapper）
- 工具：`knowledge/util/` HalfVecUtil、HashUtil、TokenEstimator
- 配置：RagConfig、AnswerCacheProperties、VisibilityCacheProperties、ReconciliationProperties

## 前端 (frontend)
- 视图：[KnowledgeView.vue](../../frontend/src/views/KnowledgeView.vue)
- 组件：[knowledge/](../../frontend/src/components/knowledge/) DocumentManager、KbFormModal、KbPermissionModal
- API：[knowledge.ts](../../frontend/src/api/knowledge.ts)
- 状态：[knowledge.ts (store)](../../frontend/src/stores/knowledge.ts)
- 路由：`/knowledge`

## Sidecar
无（检索节点由后端回调执行）。

## 设计文档
[RAG设计v6](../设计/后续其他功能设计/RAG设计v6-模块作用与通俗解读.md)、[调试手册](../项目开发进度/企业级RAG知识库-功能调试手册.md)

## 数据表
`knowledge_bases`、`knowledge_documents`、`knowledge_nodes`(chunk)、`knowledge_embeddings`(pgvector)、`knowledge_index_jobs`、`knowledge_permissions`
