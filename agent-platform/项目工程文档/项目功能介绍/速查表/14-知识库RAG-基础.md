# 14 - 知识库 RAG - 基础

## 功能简介
知识库(KB) CRUD、文档上传/解析/分块向量化(Embedding)、文档节点(chunk)查看、知识库可见性权限。属 RAG v6 精简版。

**图片/文件知识库（每文档各自选 docType + indexMode，KB 通用）：**
- `IMAGE`（图片）：MANUAL 索引（用户手填文本）｜ AUTO 索引（LLM 视觉模型识图，Phase 2）
- `FILE`（文件）：MANUAL 索引（用户手填文本）｜ AUTO 索引（Tika 自动抽文本）
- 原件字节**索引后保留**（跳过 D5 清理），检索命中经 `/asset` 端点回传（图片缩略图 / 文件下载）。详见 [14-知识库RAG-更新进度.md](14-知识库RAG-更新进度.md)。

## 后端 (backend) — `knowledge` 包
- 知识库：[KnowledgeBaseController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeBaseController.java) — `GET/POST /api/knowledge/bases` `GET/PUT/DELETE /{id}`
- 文档：[KnowledgeDocumentController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeDocumentController.java) — `POST /api/knowledge/documents/upload`（Excel 可带 `tempFileRef`+`selectedSheets`；图片/文件带 `docType`+`indexMode`+`manualIndexText`+`visionModel`）/ `POST /api/knowledge/documents/sheets/preview`（阶段1 预读 sheet 名，picker 用）/ `GET /{id}/asset`（取图片/文件原件，KB 成员可读，跨用户）/ `GET` `GET/DELETE /{id}`
- 节点：[KnowledgeNodeController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeNodeController.java) — `GET /api/knowledge/documents/{docId}/nodes`
- 权限：[KnowledgePermissionController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgePermissionController.java) — `GET POST /api/knowledge/permissions` `DELETE /{id}`
- 服务：`knowledge/service/`
  - KnowledgeBaseService、KnowledgeDocumentService、KnowledgeNodeService、KnowledgeNodeWriter、KnowledgePermissionService、VisibilitySetService
  - 内部：[knowledge/service/internal/](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/) ExtractedDocument、Section、L1Metadata、VisibleDocSet、IndexJobTxService、CitationChecker、BatchLlmResult
  - **图片/文件知识库扩展**：`KnowledgeDocumentService.upload` 按标题后缀推断 `docType`（IMAGE/FILE/EXCEL/PDF/DOCX/HTML/TEXT）+ 校验（MANUAL 必填 `manualIndexText`、IMAGE+AUTO 必填 `visionModel`）+ 合并进 `parse_options` JSON + **始终 setDocType**（补历史 NULL gap）；`streamAsset(id)` 经 `canRead` 校验后流式回传原件（IMAGE→inline，FILE→attachment）。
- 索引：DocumentParserService、IndexJobWorker（异步向量化）、配置 KnowledgeTaskExecutorConfig
  - **解析分流（按 docType）**：`extract()` switch —— `IMAGE`→`extractImage`（MANUAL 单 section 用手填文本；AUTO 视觉模型 P2）/ `FILE`→`extractFile`（MANUAL 单 section；AUTO 复用 Tika）/ `.xlsx/.xls`→[ExcelSheetExtractor](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/ExcelSheetExtractor.java)（POI，markdown 表 + 宽表行流兜底）/ 其余 → Tika。`indexMode/manualIndexText/visionModel` 从 `parse_options` JSON 读。`buildNodeMetadata` 给 IMAGE/FILE 节点 `metadata` 注入 `{fileRef,mime,originalName}` 供检索回显。
  - **原件保留（IMAGE/FILE）**：`IndexJobTxService.markDocIndexedIfDone` 返回 `IndexedDoc(docId,fileRef,docType)`；`IndexJobWorker.cleanOriginalFileAfterIndex` 据此对 IMAGE/FILE **跳过 D5 清理**（原件是回显资产），其余 docType 照常 `cleanAfterIndex`。
  - **Excel 解析分流（V39/V40）**：每 sheet → 若干 Section `title=Sheet:X:行N`，截断/降级写 `parse_warning`；`selectedSheets` 从 `parse_options` 读（空=导全部）。阈值见 [21-系统设置](21-系统设置.md) `knowledge.excel.*`。
  - **文件归属咽喉点（V40）**：`DocumentParserService.extract` 经 `FileStorageService.load(fileId, doc.getCreatedBy(), false)` 取原文 —— owner 不匹配→FORBIDDEN，根治 `GET /api/files/{id}` 既有 authenticated IDOR。`stored_files` 表记 owner。
  - **原件生命周期（D5）**：文档全部 embed 完成→`IndexJobTxService` 置 `INDEXED` 并返回 fileRef → `IndexJobWorker` 事务外 `FileStorageService.cleanAfterIndex` 删原件字节 + `stored_files.status=CLEANED`（开关 `app.files.retain-after-index=false`）。知识靠 nodes + 向量，不依赖原件。详见 [20-文件存储](20-文件存储.md)。
  - **轮询节拍**：[IndexJobWorker.poll()](../../backend/src/main/java/com/superprogrammer/knowledge/service/IndexJobWorker.java) `@Scheduled(fixedDelayString="${knowledge.index.poll-ms:5000}")` 每 5s 认领一批 PENDING/RUNNING(过期) job → 丢 `knowledgeTaskExecutor` 异步 embed。队列空也照发认领 SQL（日志刷 `SELECT ... knowledge_index_jobs ... FOR UPDATE SKIP LOCKED` `Total: 0`）→ **正常背景噪声，非故障**；想静音调高 `knowledge.index.poll-ms` 或日志级别。与记忆模块（`memoryBackfill`、`mem-task-` 线程）完全独立，别混。
- 事件：`knowledge/event/` DocumentUploadedEvent、DocumentParseListener、VisibilityInvalidationEvent、VisibilityInvalidationListener
- 实体：`knowledge/entity/` KnowledgeBase、KnowledgeDocument、KnowledgeEmbedding、KnowledgeIndexJob、KnowledgeNode、KnowledgePermission
- Mapper：`knowledge/mapper/`（含 VisibilityQueryMapper、RagRetrievalQueryMapper）
  - **证据链贯通（图片/文件回显）**：`RagRetrievalQueryMapper.fetchL1Metadata` LEFT JOIN `stored_files` 带出 `file_ref/mime/original_name` → `L1Outline→Evidence→EvidenceVO/CitationVO/CachedPayload.CitationRef` 全程携带 → 前端按 `docType` 渲染缩略图/下载。
- 工具：`knowledge/util/` HalfVecUtil、HashUtil、TokenEstimator；`FileStorageService.findMeta(fileId)` 读 stored_files 登记行（mime/originalName，回显注入用）
- 配置：RagConfig、AnswerCacheProperties、VisibilityCacheProperties、ReconciliationProperties

## 前端 (frontend)
- 视图：[KnowledgeView.vue](../../frontend/src/views/KnowledgeView.vue)
- 组件：[knowledge/](../../frontend/src/components/knowledge/) DocumentManager、KbFormModal、KbPermissionModal、**DocumentOptionsModal**（上传选项：docType + indexMode + 手填文本 + Excel sheet）、RetrievalDebugPanel（检索调试，含「来源」列回显图片/文件）、RagAskPanel、RetrievalAuditPanel、SheetPickerModal
- API：[knowledge.ts](../../frontend/src/api/knowledge.ts)（`UploadOptions` + `documentAssetUrl(docId)`）
- 状态：[knowledge.ts (store)](../../frontend/src/stores/knowledge.ts)（`uploadDocument/uploadDocumentSheets` 透传 opts）
- 路由：`/knowledge`

## Sidecar
无（检索节点由后端回调执行）。

## 设计文档
[RAG设计v6](../设计/后续其他功能设计/RAG设计v6-模块作用与通俗解读.md)、[调试手册](../项目开发进度/企业级RAG知识库-功能调试手册.md)

## 数据表
`knowledge_bases`、`knowledge_documents`（V39 加 `parse_options`/`parse_warning` 列）、`knowledge_nodes`(chunk)、`knowledge_embeddings_doubao`(pgvector，V17 建表即带 `_doubao` 后缀)、`knowledge_index_jobs`、`knowledge_permissions`、`stored_files`(V40，文件归属 owner + 生命周期)

## 前端 (frontend) — 上传（统一选项 modal）
- `DocumentManager.vue` accept 含 `.md/.txt/.pdf/.docx/.html/.xlsx/.xls` + 图片 `.png/.jpg/.jpeg/.gif/.webp/.bmp`；所有文件上传先弹 **DocumentOptionsModal** 选 `docType`+`indexMode`（MANUAL 显手填 textarea、IMAGE+AUTO P2 占位、EXCEL 显 sheet 勾选）→ confirm。
- Excel 仍两阶段（preview 读 sheet 名 → modal 勾选 → confirm 复用 `tempFileRef` 零重传）；非 Excel 直传 file + opts。
- 节点列表展开：IMAGE docType 顶部显缩略图（`/api/knowledge/documents/{id}/asset`），FILE 显下载按钮。
- `knowledge.ts`/`stores/knowledge.ts`：`previewSheets` + `uploadDocument(kbId,file,opts?)` + `uploadDocumentSheets(kbId,tempFileRef,sheets,opts?)` + `documentAssetUrl(docId)`。

## 待增删改
- ✅ 向知识库中增加图片和文件（Phase 1 落地：IMAGE/FILE + MANUAL/AUTO 索引 + 原件回显）
- ✅ excel（V39/V40 多 Sheet 导入已落地）
- ✅ Phase 2：LLM 多模态（`LlmMessage.parts` + 两 provider）+ IMAGE-AUTO 视觉识图（`DocumentParserService.extractImageByVision`）已落地
- ✅ Phase 3：聊天答案 `[n]` 引用渲染（CITATION SSE 事件 + MessageBubble「📎 引用来源」+ 缩略图/下载）已落地
- 进度细节见 [14-知识库RAG-更新进度](14-知识库RAG-更新进度.md)（P1–P3 全落）