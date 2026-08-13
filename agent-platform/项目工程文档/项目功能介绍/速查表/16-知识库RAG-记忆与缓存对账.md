# 16 - 知识库 RAG - 记忆与缓存对账

## 功能简介
答案缓存(RagAnswerCache，命中免重算 LLM)、知识库一致性对账(Reconciliation，文档/向量 orphan 检测修复)。

> ⚠️ RAG 长期记忆事实（RagMemoryFact）：**未启用的占位特性**——`RagMemoryFactMapper` 注释明确「当前无生产者写入该表（M2 软提示特性未启用）」，仅对账 sibling purge 闭环（`ReconciliationTxService.deleteDecayed`）会清理该表。勿当既有功能使用。

## 后端 (backend) — `knowledge` 包
- 答案缓存：
  - 服务：[AnswerCacheService.java](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/AnswerCacheService.java)
  - 实体：`knowledge/entity/` RagAnswerCache
  - Mapper：RagAnswerCacheMapper
  - 配置：AnswerCacheProperties
  - 版本协议：`embeddingModel + rankingConfigVersion + pipelineVersion + promptVersion + knowledgeSnapshot + permissionSignature`
  - 命中复核：候选必须匹配完整版本协议，随后逐条复核证据节点状态和 `content_hash`
  - 主动失效：权限/文档撤销、KB Ranking 配置变化按 KB 停用；管理员默认 Ranking 变化停用全部活动缓存
  - 部署可调版本：环境变量 `RAG_CACHE_PIPELINE_VERSION`、`RAG_CACHE_PROMPT_VERSION`
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
- 设置：~~settings/RagMemorySettingsTab.vue~~ **已移除**——rag-memory 开关当前前端无 UI 入口（`api/system.ts` 的 `getRagMemorySettings` 封装仍在，但无组件消费）

## Sidecar
无。

## 相关
孤儿清理（删文档同步软删 nodes + 硬删向量）见 commit `80240ee`，见 [14-知识库RAG-基础](14-知识库RAG-基础.md)。

## 数据表
`rag_memory_facts`、`rag_answer_cache`（V105 增加 ranking/pipeline/prompt/knowledge snapshot）、`knowledge_reconciliation_reports`
