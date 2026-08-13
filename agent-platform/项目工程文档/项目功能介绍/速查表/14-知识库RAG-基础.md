# 14 - 知识库 RAG - 基础

> 文档版本治理：`knowledge_documents` 是 Canonical Document 主记录，`current_version_id` 指向当前生效的不可变 `knowledge_document_versions`。新上传自动创建 v1；后续版本先保存 DRAFT，再显式生效。撤销当前版本会清空指针并立即退出检索。
>
> 元数据治理：文档可维护责任人、来源、来源更新时间、权威等级、密级、标签和生效区间。默认检索会在 SQL 层排除未来生效、已过期或无当前版本的文档；密级仅管理员可调整。
>
> 结构化解析：解析产物统一保存标题树、页码、Sheet/Cell、阅读顺序和可选 bbox；大 JSON 写文件存储，版本表只保存引用、解析器版本与 SHA-256，知识节点 metadata 保留原文定位。
>
> 类型化分块：迁移期保留旧 L0/L2 查询协议，同时在 metadata 标记 D0/S1/C2/E3。普通文档按完整段落聚合，条款/FAQ/列表/流程保持原子，表格行与视觉区域使用 E3；每个节点保留父级、前后邻居、定位、分块器版本与治理归属。
>
> Contextual Content：C2/E3 向量文本固定拼接文档标题、不可变版本、标题路径、所属背景和原文；任务保存 content/context Hash 及 parser/chunker/embedding/pipeline 版本，重试期间配置变化不会让同一任务漂移。

## 功能简介
知识库(KB) CRUD、文档上传/解析/分块向量化(Embedding)、文档节点(chunk)查看、知识库可见性权限。属 RAG v6 精简版。

**图片/文件知识库（每文档各自选 docType + indexMode，KB 通用）：**
- `IMAGE`（图片）：MANUAL 索引（用户手填文本）｜ AUTO 索引（LLM 视觉模型识图，Phase 2）
- `FILE`（文件）：MANUAL 索引（用户手填文本）｜ AUTO 索引（Tika 自动抽文本）
- 原件字节**索引后保留**（跳过 D5 清理），检索命中经 `/asset` 端点回传（图片缩略图 / 文件下载）。详见 [14-知识库RAG-更新进度.md](14-知识库RAG-更新进度.md)。

## 后端 (backend) — `knowledge` 包
- 知识库：[KnowledgeBaseController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeBaseController.java) — `GET/POST /api/knowledge/bases` `GET/PUT/DELETE /{id}`
- 文档：[KnowledgeDocumentController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeDocumentController.java) — 上传/预读/原件/版本接口；`PUT /api/knowledge/documents/{id}/metadata` 更新治理字段并写审计、失效缓存。
- 节点：[KnowledgeNodeController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeNodeController.java) — `GET /api/knowledge/documents/{docId}/nodes`
- 权限：[KnowledgePermissionController.java](../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgePermissionController.java) — `GET POST /api/knowledge/permissions` `DELETE /{id}`
- 服务：`knowledge/service/`
  - KnowledgeBaseService、KnowledgeDocumentService、KnowledgeNodeService、KnowledgeNodeWriter、KnowledgePermissionService、VisibilitySetService
  - 内部：[knowledge/service/internal/](../../backend/src/main/java/com/superprogrammer/knowledge/service/internal/) `ExtractedDocument/Section/SectionLocator/BoundingBox` 定义结构化解析协议；`StructuredDocumentExtractor` 负责 PDF、Markdown、DOCX、图片定位；`ParseArtifactService` 把解析 JSON 写文件存储并绑定当前版本。
  - **图片/文件知识库扩展**：`KnowledgeDocumentService.upload` 按标题后缀推断 `docType`（IMAGE/FILE/EXCEL/PDF/DOCX/HTML/TEXT）+ 校验（MANUAL 必填 `manualIndexText`、IMAGE+AUTO 必填 `visionModel`）+ 合并进 `parse_options` JSON + **始终 setDocType**（补历史 NULL gap）；`streamAsset(id)` 经 `canRead` 校验后流式回传原件（IMAGE→inline，FILE→attachment）。
- 索引：DocumentParserService、IndexJobWorker（异步向量化）、配置 KnowledgeTaskExecutorConfig
  - **分块策略**：`knowledge/chunk/ChunkFactory` 按 Section `nodeType` 路由。普通文档使用 `NormalDocumentChunkStrategy`，目标 300～600 token、完整段落 overlap 不超过 100 token；条款/FAQ/列表/流程使用 `AtomicTypeChunkStrategy`，只有超过 600 token 才安全硬切；TABLE_ROW、VISUAL_REGION、PAGE 分别映射为 E3/TABLE_ROW、E3/VISUAL_REGION、C2/PDF_PAGE。
  - **双轨兼容**：D0 仍由文档 `l1_metadata` 承载，S1 物理写旧 `L0`，C2/E3 物理写旧 `L2`；真实 `granularity/chunkType/chunkerVersion/chunkOrdinal/parentPath/previousPath/nextPath` 保存在 metadata，现有 PG 检索无需同时重写。
  - **上下文化索引**：`Contextualizer` 生成稳定文本和 `contextHash`；`KnowledgeNodeWriter` 为 S1/C2/E3 创建带完整版本指纹的幂等 Outbox；`IndexJobWorker` 使用任务锁定模型向量化并在调用前复核版本与 Hash；`IndexJobTxService` 在短事务内再次复核、upsert 向量、完成任务并判断文档 INDEXED。
  - **可配置 Pipeline**：`rag.index.pipeline-version`（环境变量 `RAG_INDEX_PIPELINE_VERSION`）决定索引编排版本；Embedding 模型取知识库显式配置，未配置明确失败，不静态指定模型。
  - **解析分流（按 docType/后缀）**：IMAGE 输出第 1 页图片区域；Excel 用 POI 输出 Sheet/行/Cell；PDF 用 PDFBox 逐页；Markdown 与 DOCX 保留标题树；其他格式走 Tika 并补阅读顺序。`buildNodeMetadata` 的文件回显字段会与 Section locator 合并，不相互覆盖。
  - **解析产物（V108）**：每个已版本化文档在摘要/节点落库前生成结构化 JSON，经 `FileStorageService.storeStream` 保存；`knowledge_document_versions` 记录 `parser_version/parse_artifact_ref/parse_artifact_hash/parsed_at`。PDF 最多 2000 页、全文最多 2000 万字符；不执行宏/脚本/外链，拿不到 bbox 时保持空值。
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
- 组件：[knowledge/](../../frontend/src/components/knowledge/) DocumentManager、KbFormModal、KbPermissionModal、**DocumentOptionsModal**（上传选项）、**DocumentMetadataModal**（来源/权威/密级/标签/有效期治理）、RetrievalDebugPanel、RagAskPanel、RetrievalAuditPanel。
- API：[knowledge.ts](../../frontend/src/api/knowledge.ts)（`UploadOptions` + `documentAssetUrl(docId)`）
- 状态：[knowledge.ts (store)](../../frontend/src/stores/knowledge.ts)（`uploadDocument/uploadDocumentSheets` 透传 opts）
- 路由：`/knowledge`

## Sidecar
无（检索节点由后端回调执行）。

## 设计文档
[RAG设计v6](../设计/后续其他功能设计/RAG设计v6-模块作用与通俗解读.md)、[调试手册](../项目开发进度/企业级RAG知识库-功能调试手册.md)

## 数据表
`knowledge_bases`、`knowledge_documents`（V107 加治理字段和有效期/权威/密级/标签索引）、`knowledge_document_versions`（V108 加解析产物引用/Hash/解析器版本/解析时间）、`knowledge_nodes`、`knowledge_embeddings_doubao`、`knowledge_index_jobs`（V109 加文档/解析器/分块器/向量模型/Pipeline 版本指纹）、`knowledge_permissions`、`stored_files`。

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
