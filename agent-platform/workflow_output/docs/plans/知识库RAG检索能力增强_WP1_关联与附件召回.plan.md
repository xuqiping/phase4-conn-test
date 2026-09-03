---
description: "C1 文档关联关系图 + C2 附件式整文件召回的实现计划（WP1）"
created-date: 2026-09-03
---

# Implementation Plan for WP1：关联与附件召回

> 上级索引：[知识库RAG检索能力增强.plan.md](知识库RAG检索能力增强.plan.md)｜规格：[§3 C1](../specs/知识库RAG检索能力增强设计.md)、[§4 C2](../specs/知识库RAG检索能力增强设计.md)

## 坑点预判（WP1 内）

| 坑 | 规避 | 验证 |
|---|---|---|
| step6.5 逐 doc 查边 N+1（20 命中=20 查询） | 单条 `IN (docIds)` 批查正反两向 | 单测断言 mapper 调用次数=1 |
| 权限复校逐 doc 判 canRead 又一轮 N+1 | 同库边限定：边全在同库 → 调用方对该库的权限一次判定即覆盖（同库=同 ACL）；保密库成员特殊：带出文档仅注入内容、fileRef 走既有 403 链路 | 同库单测；跨库建边被拒的校验用例 |
| MAY_CITE 追加重打分 = 每次多一次 rerank 调用（+250ms） | 仅当存在 MAY_CITE 边且候选非空才调；候选去重后 ≤ 边数上限 20 | trace 计量增量 ≤300ms |
| token 挤占顺序错（先挤原始命中） | 挤占只从 MAY_CITE 注入项开始，MUST_CITE 与原始命中不动 | 预算压到极限的构造用例 |
| AnswerCache 陈旧（主计划坑 1） | evidence hash 追加「命中文档相关的边集合 hash」+「附件注入内容 hash」 | 建边后同 query 旧缓存 miss 重算 |
| 图片 VLM 实时调用阻塞 3s+ | Redis 缓存（key=sha256(fileRef+visionModel+promptVer)，TTL 30d）+ 2.5s 超时→降级仅描述注入并标注「原件内容暂缺」 | 首次慢二次快；超时注入降级文案 |
| ATTACHMENT 被旧解析路径误切章节 | 解析入口先判 indexMode=ATTACHMENT → 单节点构造（复用 MANUAL 单 section 路径） | 上传 txt ATTACHMENT → nodes 数=1 |
| 附件全文注入超预算 | 8000 字截断+「已截断可下载原件」标注；注入量计入证据预算 | 20k 字文件用例断言截断标注 |

## 实现步骤

- [x] **Step 1：C1 数据层与关系服务**（commit cad03722；V170）
  - **目标**：两表落库；建边/删边/查询可用且挡住非法边
  - **动作**：①迁移 `V1xx__knowledge_document_relations.sql`（relations + relation_suggestions 两表，规格 §3.1/§3.3 DDL）；②实体+Mapper；③`DocumentRelationService`：create（校验：同库、非自环、两文档存在且 ACTIVE、语义等价反向边查重→拒）、delete、listByDoc（双向解释：入边 MAY_CITE 出边=MAY_BE_CITED 反读，见规格 §3.1）；④建边/删边 @AuditLog；⑤Controller：POST/DELETE/GET /api/knowledge/relations（canManage）
  - **文件**：迁移 ×1、`knowledge/relation/DocumentRelationService.java`、`DocumentRelationController.java`、实体 ×2、Mapper ×2、Test ×1
  - **依赖**：无｜**验证**：单测——四类型建边/双向读、自环拒、跨库拒、语义等价拒、无 canManage 403 ✅ 10/10

- [x] **Step 2：C1 step6.5 关系图后处理**
  - **目标**：rerank 后按边带出文档，安全且计量
  - **动作**：①`RelationGraphPostProcessor.process(hitDocs, user, budget)`：批查边（IN 正反两向，1 跳硬限）→ 去重 → 权限复校（同库单判定，无权静默丢）→ MUST_CITE 直接进证据（injectedBy=RELATION_MUST）/MAY_CITE 追加 rerank 重打分（过阈才进，injectedBy=RELATION_MAY）→ token 预算挤占从 MAY 开始；②`RagRetrievalService` 在证据组装前插入调用（若 WP2 已合入：**循环结束后执行一次**）；③EvidenceVO 加 injectedBy 字段；④AnswerCache evidence hash 计算扩边集合 hash；⑤trace step6.5 段计量（耗时/带出/丢弃）
  - **文件**：`knowledge/relation/RelationGraphPostProcessor.java`、`RagRetrievalService.java`、`dto/RagRetrieveVO.java`、`service/internal/AnswerCacheService.java`、`trace/RagTraceContext.java`、Test ×2
  - **依赖**：Step 1｜**验证**：单测——去重/1跳（A→B→C 不带C）/权限丢弃静默/预算挤占顺序/缓存失效；集成——无任何边时输出与基线一致
  - **实现注**（偏离 2 处，详见开发进度）：①缓存失效走 `computeKnowledgeSnapshot` SQL 双聚合（nodes+边）而非 AnswerCacheService 单独 hash 段——P3 校验链单点收口，部署日存量缓存全量 miss 一次属预期；②step6.5 计量走 MDC traceId 结构化日志（processor 一条 info：边/MUST/MAY/相关文档/权限丢弃/耗时）+ writeTrace evidence 行加 injectedBy 列，不动 RagTraceContext（该类设计为「只存 ID/用途」，加计数列需 DDL+新表，收益不成比例）；MAY_CITE 重打分复用 rankWithTrace（真实 RankingEngine 调用，DISABLED 模式下候选保守淘汰）。运维补：`rag.recall.relation.enabled` kill switch + `per-doc-l2-cap`（RagRecallProperties.Relation，主计划运维清单配置开关行的 C1 项）。测试 16/16（processor 12 + merge 4）✅；全量 2761/2761

- [x] **Step 3：C1 关联建议**
  - **目标**：共召回统计自动建议关联
  - **动作**：①`RelationSuggestionWorker`（@Scheduled 每日）：扫 trace 表近期共召回对（同 query ≥3 次共现、无既有边、同库）→ upsert suggestions；②采纳 API（POST /relations/adopt/{suggestionId}→建边+删建议）/忽略 API；③仅 owner/canManage 可见可操作
  - **文件**：`RelationSuggestionWorker.java`、`DocumentRelationController.java`（扩展）、`trace` 读侧 Mapper、Test ×1
  - **依赖**：Step 1（trace 表已有）｜**验证**：构造共召回数据→建议生成→采纳→边出现→建议消失
  - **实现注**（偏离 3 处，详见开发进度）：①采纳后建议不物理删——置 ADOPTED/IGNORED 状态位占住 uq_kdrs(kb,a,b)，worker 据此不重提（用户已裁决的对不再打扰），等价防重且留审计痕迹；②采纳不直接写边表——委托 `DocumentRelationService.create` 复用六路校验（建议流不能绕过建边不变式），建议生成后用户手动建过边 → 采纳遇「已存在」按成功收口；③trace 读侧零新 Mapper——复用 `RagRetrievalLogMapper.selectList`（cursor 分批 LIMIT 500）；Controller 实为 `KnowledgeRelationController` 扩展（GET /suggestions + POST /{id}/adopt + /{id}/ignore）。测试 18/18（worker 9 + service 9）✅

- [x] **Step 4：C1 前端**
  - **目标**：关联可管理、可见、可追溯
  - **动作**：①`DocumentRelationPanel.vue`：文档抽屉新 Tab（出边/入边列表+类型徽标+删除+添加弹窗=同库文档选择器+四选一+备注）；②建议列表页（采纳/忽略）；③`RetrievalDebugPanel.vue`：证据 injectedBy 徽标「🔗 关联带出」、MAY_BE_CITED 反读「相关文档」尾区；④api/knowledge.ts 扩接口
  - **文件**：`DocumentRelationPanel.vue`（新）、`DocumentManager.vue`（挂 Tab）、`RetrievalDebugPanel.vue`、`src/api/knowledge.ts`、Test ×1
  - **依赖**：Step 1-3｜**验证**：vitest 装配+权限显隐（无 canManage 无添加钮）；手测建边→检索带出
  - **实现注**（偏离 1 处）：「文档抽屉新 Tab」实为 `DocumentRelationModal.vue` 行级弹窗（模块无抽屉容器，版本弹窗即先例）；建议页=`DocumentRelationSuggestionModal.vue`（DocumentManager 工具区「关联建议」入口，仅 canManage）。行级「关联」钮成员可见（看边列表理解 🔗 证据来源），建/删边弹窗内 canManage 显隐。测试 10/10（modal 8 + manager 扩 2）；手测剧本（建边→检索带出/旧缓存 miss）留 Phase4 统一跑

- [x] **Step 5：C2 ATTACHMENT 模式入库**
  - **目标**：附件型上传：整件入库不切片，描述必填
  - **动作**：①`KnowledgeDocumentService` indexMode 校验扩 ATTACHMENT（描述必填 ≤4000 字+关键词可选；docType 任意）；②`DocumentParserService.extractAttachment`：不走解析器，单节点构造（复用 MANUAL 单 section 路径），metadata 记 {attachMode:true, attachmentText}——文本类（白名单后缀）读原件全文 ≤8000 字存 attachmentText；PDF/DOCX/XLSX 调 Tika 提取全文截断存；图片不预提取（检索时实时）；③node metadata 注 fileRef 链路复用现状
  - **文件**：`KnowledgeDocumentService.java`、`DocumentParserService.java`、Test ×2
  - **依赖**：无｜**验证**：单测——ATTACHMENT 校验矩阵（缺描述拒/超长拒/图片不预提取/文本类 attachmentText 截断）；上传 txt/pdf/图片三型 nodes 数=1
  - **实现注**：校验抽 `validateIndexText` 包私有静态（MANUAL/ATTACHMENT 同门 ≤4000，供单测矩阵）；全文预提取落点在 `buildNodeMetadata`（单次文件读，extract 零 IO——附件 section 只装描述+关键词）；白名单 11 后缀直读 UTF-8，其余 Tika 限 8001 字，失败/空 → null 降级不阻断；extract/buildNodeMetadata/loadAttachmentText 放开包私有供单测（scanInjection 先例）。测试 13/13；txt/pdf/图片三型 nodes=1 留 Phase4 手测（需真文件入库）

- [ ] **Step 6：C2 命中注入 AttachmentContentInjector**
  - **目标**：附件命中后真实内容进上下文
  - **动作**：①`AttachmentContentInjector.inject(evidence)`：metadata.attachMode 命中 → 文本类读 attachmentText；图片读 Redis 视觉缓存 miss 则调 VLM（2.5s 超时降级仅描述，计费归户 docOwner，写缓存）；②注入内容格式 `[附件 {originalName}] 内容：…`（截断标注）；计入证据预算（超 8000 字可配）；③`RagRetrievalService` 证据组装处调用；④evidence hash 扩附件注入内容 hash；⑤CitationVO 渲染 📎 徽标（docType 已有，前端补样式）
  - **文件**：`knowledge/attachment/AttachmentContentInjector.java`、`AttachmentVisionCache.java`、`RagRetrievalService.java`、`AnswerCacheService.java`、`LlmGateway`（视觉调用复用现有识图通道）、Test ×2
  - **依赖**：Step 5｜**验证**：单测——三型注入分流/超时降级/缓存命中不调 VLM（gateway 计数）/保密库附件 fileRef 403 但注入正常；手测：传架构图问图中内容

- [ ] **Step 7：C2 前端**
  - **目标**：上传三选+附件标识+调试预览
  - **动作**：①上传弹窗 indexMode 三选（智能解析/手动索引/附件模式），ATTACHMENT 表单=描述必填+关键词可选；②文档列表「📎」徽标；③检索调试附件型证据显示注入内容预览（截断）
  - **文件**：上传组件（`DocumentManager.vue` 或独立 UploadModal）、`RetrievalDebugPanel.vue`、Test ×1
  - **依赖**：Step 5-6｜**验证**：vitest 表单校验分支；手测三选切换表单联动

## 联动点（WP1 专属细化）

主计划 L1/L2/L3/L4 之外补充：附件模式文档删除→视觉缓存 TTL 自然过期（无主动清理，30d 兜底）；文档新版本上传→ATTACHMENT 重新走 Step 5 提取（attachmentText 换新）。

## 验证汇总

- [ ] 单测新增 ~20（关系 8/注入 8/建议 2/前端 2）
- [ ] 集成：无边无附件时管道输出与基线逐字节一致（WP1 自身的零回归门）
- [ ] 手测剧本：术语表 MUST_CITE 差旅制度→问差旅带术语表引用；架构图附件→问图内容；建边后旧缓存答案失效重算
