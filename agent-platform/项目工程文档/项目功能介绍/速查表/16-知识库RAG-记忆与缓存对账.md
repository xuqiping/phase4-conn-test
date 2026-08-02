# 16 - 知识库 RAG - 记忆与缓存对账

## 功能简介
RAG 长期记忆事实(RagMemoryFact)、答案缓存(RagAnswerCache，命中免重算 LLM)、知识库一致性对账(Reconciliation，文档/向量 orphan 检测修复)。

## 后端 (backend) — `knowledge` 包
- 答案缓存：
  - 服务：[AnswerCacheService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/AnswerCacheService.java)
  - 实体：`knowledge/entity/` RagAnswerCache
  - Mapper：RagAnswerCacheMapper
  - 配置：AnswerCacheProperties
- RAG 记忆事实：
  - 实体：`knowledge/entity/` RagMemoryFact
  - Mapper：RagMemoryFactMapper
- 对账：
  - 服务/Worker：[ReconciliationWorker.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/ReconciliationWorker.java)、内部 ReconciliationTxService
  - 实体：`knowledge/entity/` KnowledgeReconciliationReport
  - Mapper：KnowledgeReconciliationReportMapper
  - 配置：ReconciliationTaskExecutorConfig、ReconciliationProperties

## 前端 (frontend)
- 组件：[knowledge/RetrievalAuditPanel.vue](../../frontend/src/components/knowledge/RetrievalAuditPanel.vue)（审计/对账展示）
- API：[knowledge.ts](../../frontend/src/api/knowledge.ts)
- 设置：[settings/RagMemorySettingsTab.vue](../../frontend/src/components/settings/RagMemorySettingsTab.vue)（RAG 记忆开关）

## Sidecar
无。

## 相关
孤儿清理（删文档同步软删 nodes + 硬删向量）见 commit `925979f`，见 [14-知识库RAG-基础](14-知识库RAG-基础.md)。

## 数据表
`rag_memory_facts`、`rag_answer_cache`、`knowledge_reconciliation_reports`
