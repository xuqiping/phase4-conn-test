# 14 - 知识库 RAG - 图片/文件知识库 更新进度

> 本文档跟踪「图片知识库 + 文件知识库」新功能落地进度。每次阶段完成更新。
> 基础功能见 [14-知识库RAG-基础.md](14-知识库RAG-基础.md)。

## 需求

知识库新增两类文档（**每文档各自选**，KB 通用，上传时由用户选）：

| docType | MANUAL 索引（用户手填文字） | AUTO 索引 |
|---|---|---|
| `IMAGE`（图片） | 用户填索引文本 → 向量化 | **LLM 视觉模型**识图生成文本 → 向量化（模型用户自选） |
| `FILE`（文件） | 用户填索引文本 → 向量化 | Tika 自动抽文本（pdf/docx/html/txt 等） |

检索命中后**回传图片/文件本体**：调试面板 + 节点列表 + 聊天 `[n]` 引用 三处回显（缩略图 / 下载链）。

## 关键设计

- `docType` 字段当前上传从不写（全 NULL）。新功能**始终 setDocType**（按后缀推断 + 用户覆盖），旧 doc 不动照常召回。
- `indexMode`/`manualIndexText`/`visionModel` 塞进现有 `parse_options` JSON（不新加列）。
- **原件保留**：`IndexJobWorker.cleanOriginalFileAfterIndex` 对 IMAGE/FILE 跳过 D5 清理（两模式都跳），保证 `GET /api/knowledge/documents/{docId}/asset` 取得到字节。
- **证据链贯通**：`KnowledgeNode.metadata` 注入 `{fileRef,mime,originalName}`；`fetchL1Metadata` SQL JOIN `stored_files`；`L1Outline→Evidence→EvidenceVO/CitationVO` 全程带 `fileRef/mime/docType/originalName`。
- **取件端点**：新 `GET /api/knowledge/documents/{docId}/asset`，`canRead` 校验后流式返回（KB 成员都能取，跨用户）。
- **LLM 多模态**（P2）：`LlmMessage` 加 `List<ContentPart> parts`（text/image），null 走老路（零行为变化）；`ClaudeProvider`/`OpenAICompatibleProvider.buildRequestBody` 有 parts 时发 content 数组（Claude base64 / OpenAI image_url）。模型按 name 字符串选，复用 `AvailableModelVO` + 前端 `ModelSelector.vue`。
- **聊天回显**（P3）：`/api/knowledge/ask` 已发 CITATION 事件（`StreamEvent.citation` 带 `CitationVO[]`），前端 store 现忽略 → 接事件存 `message.metadata.citations`，`MessageBubble` 解析 `[n]` 内联缩略图/下载 chip。

## 阶段效果总结（P1–P3）：专业术语 + 大白话 + 案例

> 按「后端 / 前端」分别说明每阶段达成效果。专业术语讲清改了什么、大白话讲清用户能干什么、案例给真实触发路径。

### 一、后端达到的效果

#### P1 — 多类型文档接入 + 原件留存 + 取件端点 + 证据链贯通

- **专业术语**：
  - 扩展文档类型枚举 `docType = IMAGE | FILE` 与索引模式 `indexMode = MANUAL | AUTO`，新参数（`manualIndexText`/`visionModel`）塞进既有 `parse_options` JSON，**零 schema 变更**（不加列、不迁库）。
  - `DocumentParserService.extract` 改为 `switch(docType)` 分发：新增 `extractImage` / `extractFile`；FILE-AUTO 复用 Tika 抽文本（pdf/docx/html/txt/xlsx）；MANUAL 走 `manualExtracted` 单 section。
  - `IndexJobWorker.cleanOriginalFileAfterIndex` 对 IMAGE/FILE **跳过 D5 原件清理**（两模式都跳），保证字节可取。
  - 新增 `GET /api/knowledge/documents/{docId}/asset` 取件端点：`canRead` 鉴权后 inline（图片）/ attachment（文件）流式返回，KB 成员跨用户可读。
  - 证据链贯通：`KnowledgeNode.metadata` 注入 `{fileRef,mime,originalName}` → `RagRetrievalQueryMapper.fetchL1Metadata` SQL LEFT JOIN `stored_files` → `EvidenceVO/CitationVO` 全程透传 `fileRef/mime/docType/originalName`。
- **大白话**：以前知识库只装得了「一段段文字」，现在能装「图片」和「文件」。上传时告诉系统这是图还是文件、索引文字是自己填还是让系统自动抽。**原件不删**，留着后面取。给每个文档开了个「取原件」接口，有权限才能下。检索链路从头到尾把「这是图、原件在哪、什么格式、叫啥名」一路带到底，不丢。
- **案例**：传 `报销规范.pdf`（FILE+AUTO）→ Tika 抽出文字 → 向量入库；另传 `产品截图.png`（IMAGE+MANUAL，手填「登录页报错弹窗」）→ 向量入库。检索「登录报错」→ 命中截图 → `GET /documents/{id}/asset` 返回 png 原图字节。

#### P2 — LLM 多模态识图（图片 AUTO）

- **专业术语**：
  - `LlmMessage` 增 `List<ContentPart> parts`（text/image 二元结构），null/空走原 `content` 字符串路径（**零行为变化**，老调用不受影响）。
  - `ClaudeProvider.buildRequestBody`：parts 非空发 `{type:image, source:{type:base64, media_type, data}}`；`OpenAICompatibleProvider`：发 `{type:image_url, image_url:{url:"data:mime;base64,..."}}`。
  - `DocumentParserService.extractImage` AUTO 分支：读字节→base64→视觉 `LlmRequest`→生成文本 section 复用 `manualExtracted`。
  - 附带修 `ClaudeProvider.parseResponse`：thinking 模型首个 content block 是 `{type:thinking}` 而非 `{type:text}`，原读 `/content/0/text` 取空 → 改为遍历 `content[]` 取 `type=text` 拼接。
- **大白话**：图片不用人手动写描述了，让大模型「看图说话」自动生成文字再入库。两种主流协议（Claude / OpenAI 兼容）都能发图。没图的消息完全不受影响。识图准不准 = 模型本身能力（非代码 bug）。
- **案例**：传含「REDSTONE Vision Test / hongshi 2026」字样的图片（IMAGE+AUTO，选 `doubao-seed-2.0-code`）→ 模型 OCR 出原文 → 入库 → 检索「hongshi」命中。`glm-5.1` 非多模态 → 忽略 image 块产生幻觉（模型能力问题）。

#### P3 — chat 流补发 CITATION + 修 P1 遗漏

- **专业术语**：
  - `ChatSessionService.RagInjection` record 增 `List<CitationVO> citations`，`resolveRagForChat` 透传 `EvidenceResult.getCitations()`。
  - `doSendMessageStream` 的 `concatWith(Flux.defer)` 块在 `DONE` 前发 `StreamEvent.citation(json)`（citations 非空才发）。
  - `ChatWebSocketHandler` 增 CITATION 分支转发 `content`（WS 原仅映射 CHUNK/THINKING/INPUT_REQUIRED，**会静默丢 CITATION**）。
  - **修 P1 遗漏**：`RagRetrievalService` 稠密检索路径（`pack.injected().stream().map(CitationVO.builder...)`）原只设 `index/documentId/title/nodeId`，**漏 `docType/fileRef/mime/originalName` 4 字段**（其余 3 个 builder 本就有）→ chat CITATION 的 IMAGE 字段全 null。补齐后 E2E 通过。
- **大白话**：以前只有独立的「知识问答」页会把「答这题用了哪些资料」告诉前端；聊天页虽然也检索了知识库，但**没把引用清单发给前端**。现在聊天流式回复收尾前，把引用清单（含图/文件位置）一并推给前端。顺带修了 P1 埋的坑：稠密检索那条路拼引用对象时漏带了图/文件的 4 个关键字段。
- **案例**：聊天问「红石测试图」→ 答案含 `[1]` → CITATION 帧带 `{documentId:21, docType:IMAGE, fileRef:/api/files/xx.png, mime:image/png, originalName:cite_img.png}`。

---

### 二、前端达到的效果

#### P1 — 上传选项弹窗 + 文档列表/调试面板回显

- **专业术语**：
  - 新 `DocumentOptionsModal.vue`：docType 选择器 + indexMode radio + MANUAL textarea + IMAGE+AUTO 的 P2 占位 + Excel sheet 勾选。
  - `DocumentManager.vue`：`customUpload` 统一走 modal；`accept` 加图片后缀；`renderDocNodes` 顶部对 IMAGE 渲染缩略图、FILE 渲染下载按钮。
  - `api/knowledge.ts`：新增 `UploadOptions` 类型 + `appendUploadOptions`（multipart 注入）+ `documentAssetUrl(docId)`；`stores/knowledge.ts` 透传 opts。
  - `RetrievalDebugPanel.vue`：`citationCols` / `l2Cols` 增「来源」列，`renderAssetCell` 渲染缩略图/下载链。
- **大白话**：上传弹窗能选「图片/文件 + 手填/自动」；文档列表里图片直接显缩略图、文件显下载键；检索调试面板多一列「来源」能直接看证据本体。
- **案例**：传一张 png → 文档列表顶部直接看到该图缩略图；点 xlsx 旁下载键 → 直接下原件。

#### P2 — 视觉模型选择器

- **专业术语**：`DocumentOptionsModal` 在 IMAGE+AUTO 时渲染 `ModelSelector.vue`（复用 chat 模块），`canConfirm` 校验要求必选模型；模型按 name 字符串透传后端，复用 `AvailableModelVO`。
- **大白话**：图片走自动识图时，让你挑哪个大模型来看图。
- **案例**：选 `doubao-seed-2.0-code` → 后端按 OpenAI 协议发 image_url；选 `kimi k2.6` → 按 Anthropic 协议发 image source base64。

#### P3 — 聊天气泡引用回显（`[n]` 内联）

- **专业术语**：
  - `stores/chat.ts`：SSE 与 WS 双 `switch` 增 `case 'CITATION'` → `JSON.parse(evt.content)` 暂存 `streamingCitations` ref，`DONE` / `MESSAGE_COMPLETE` 时并入 `message.metadata.citations`。
  - `MessageBubble.vue`：底部渲染「📎 引用来源」列表，按 `docType` 分支：`IMAGE` → `<img :src="knowledgeApi.documentAssetUrl(docId)">` 缩略图；`FILE` → `<a :href="..." :download>` 下载 chip；`null` → 仅显示标题。
- **大白话**：AI 回复下方多一块「引用来源」，引用的是图就显小图、是文件就显下载键，点开能看/下原件。答案里的 `[1][2]` 对应列表第几条。
- **案例**：AI 答「详见登录报错 [1]」→ 气泡下方 `[1] 产品截图.png` 旁直接显示该截图缩略图；若是 pdf → 显示「⬇ 下载原件」按钮。

---

## 阶段清单

### Phase 1 — 上传+解析+保留原件+证据链+取件端点+前端上传/回显（图片MANUAL、文件MANUAL/AUTO 全通，不碰 LLM）✅

**后端（mvn compile ✅）：**
- [x] Controller.upload 加 4 参（docType/indexMode/manualIndexText/visionModel）+ GET /{id}/asset 端点
- [x] Service 推断 docType（resolveDocType 按后缀）+ 校验（MANUAL 必填文本 / IMAGE+AUTO 必填 visionModel）+ buildParseOptions 合并 + 始终 setDocType + toVO 加 mime/originalName/indexMode + streamAsset（canRead 校验 + inline/attachment）
- [x] DocumentParserService.extract 改 switch on docType；新增 extractImage/extractFile（MANUAL→manualExtracted 单 section；FILE-AUTO→extractTika 复用）+ buildNodeMetadata 注入 + readIndexOption
- [x] IndexedDoc record + IndexJobTxService.markDocIndexedIfDone/completeUpsert/L1 返回 IndexedDoc + IndexJobWorker.cleanOriginalFileAfterIndex 跳 IMAGE/FILE
- [x] KnowledgeNodeWriter.buildNode 用 metadataJson（IMAGE/FILE 注入 fileRef/mime/originalName）
- [x] FileStorageService.findMeta(fileId)
- [x] RagRetrievalQueryMapper.fetchL1Metadata SQL LEFT JOIN stored_files 带 file_ref/mime/original_name
- [x] RagQueryRow.L1Row + L1Outline + Evidence + EvidenceVO + CitationVO + CachedPayload.CitationRef 全链路加 fileRef/mime/docType/originalName
- [x] KnowledgeDocumentVO 加 mime/originalName/indexMode

**前端（vue-tsc ✅）：**
- [x] 新 DocumentOptionsModal.vue（docType 选 + indexMode radio + MANUAL textarea + IMAGE+AUTO P2 占位 + Excel sheet 勾选）
- [x] DocumentManager.vue：customUpload 统一走 modal；accept 加图片后缀；renderDocNodes 顶部 IMAGE 缩略图 / FILE 下载按钮
- [x] api/knowledge.ts（UploadOptions + appendUploadOptions + documentAssetUrl + 接口字段）+ stores/knowledge.ts 透传 opts
- [x] RetrievalDebugPanel citationCols/l2Cols 加「来源」列（renderAssetCell）

### Phase 2 — LLM 多模态 + 图片 AUTO ✅

- [x] LlmMessage 加 `List<ContentPart> parts`（DTO + ContentPart 嵌套；保留 2 参构造向后兼容）
- [x] ClaudeProvider.buildRequestBody 有 parts 发 Claude content 数组（image source base64 / text）
- [x] OpenAICompatibleProvider.buildRequestBody 有 parts 发 image_url（data:mime;base64,...）
- [x] DocumentParserService.extractImage AUTO 分支：读字节 base64 → 视觉 LlmRequest → 生成文本 section（复用 manualExtracted）
- [x] DocumentOptionsModal：IMAGE+AUTO 时显 ModelSelector（复用 chat/ModelSelector.vue，canConfirm 要求选模型）
- [x] mvn compile + vue-tsc + E2E（图片 AUTO 上传→识图→检索）

### Phase 3 — 聊天 [n] 引用回显 ✅

- [x] 后端补发 CITATION：`ChatSessionService.RagInjection` 加 `citations` 字段透传 `EvidenceResult.citations`；流式 `concatWith` DONE 前发 `StreamEvent.citation(json)`（SSE 自动转发）；`ChatWebSocketHandler` 加 CITATION 分支转发 `content`（WS 端对齐 SSE）。
- [x] **修 P1 遗漏**：`RagRetrievalService` 稠密检索路径 CitationVO builder 只设 index/documentId/title/nodeId，丢 docType/fileRef/mime/originalName → 补齐（其余 3 个 builder 本就有）。
- [x] stores/chat.ts：SSE `case 'CITATION'` + WS `case 'CITATION'` → `JSON.parse(content)` → 暂存 `streamingCitations`，DONE/MESSAGE_COMPLETE 并入 `message.metadata.citations`。
- [x] MessageBubble.vue：底部「📎 引用来源」列表，按 `docType` 渲染（IMAGE→`<img :src=knowledgeApi.documentAssetUrl(docId)>` 缩略图 / FILE→下载 chip / null→仅标题）。
- [x] E2E（mvn compile ✅ + vue-tsc ✅ + 后端重启 + curl）：KB4 含 IMAGE-MANUAL doc(21) + 旧 xlsx(6) → 流式 chat → 答案含 `[1]` → CITATION 帧带 `{documentId:21,docType:IMAGE,fileRef:/api/files/...png,mime:image/png,originalName:cite_img.png}`；`GET /documents/21/asset` → 200 image/png 2227B（bubble `<img>` 可渲染）。旧 xlsx(6) asset 404（P1 前 D5 已清原件，非回归）。

## 状态

- 2026-07-07：方案定稿（A 改 gateway / 3 阶段 / 每文档各自选）。
- 2026-07-07：**P1 完**（mvn compile ✅ + vue-tsc ✅）。图片 MANUAL / 文件 MANUAL+AUTO 全链路通；IMAGE-AUTO（视觉模型）P2。未提交。待重启后端 + E2E 验证。
- 2026-07-08：**P1 E2E 验证通过**（后端重启 + 全链路 curl E2E）。IMAGE-MANUAL / FILE-MANUAL / FILE-AUTO(Tika) 三路径：上传→索引(INDEXED)→取件端点(IMAGE inline image/png 70B PNG magic / FILE attachment text/plain 120B 中文完整)→检索 evidenceL2 全链路带 `docType/fileRef/mime/originalName/citationIndex/rerankScore`。MANUAL manualIndexText UTF-8 存取正确。遗留(非阻塞,仅文档)：`KnowledgeBaseRequest` 注释「Phase1 默认 doubao」应改 `doubao-embedding-vision`（后端 `DEFAULT_EMBEDDING_MODEL` 实际已正确，空白时走默认；显式传 `doubao` 会 DEAD）。
- 2026-07-08：**P2 完**（mvn compile ✅ + vue-tsc ✅ + E2E 真识图通过）。`LlmMessage.parts` 零行为变化（null/空走老 content 字符串）；Claude/OpenAI 两 provider parts 非空发 content 数组。E2E：PIL 生成含已知文字图片（REDSTONE Vision Test / hongshi 2026 / Layer: Gateway Memory Retrieval 三色字+蓝框）→ IMAGE+AUTO → 模型 OCR 返回精确匹配原文 → 索引 → 检索 evidenceL2 命中。诊断日志确认请求体 `blocks=[image,text]`。**两个视觉模型均真识图**：`doubao-seed-2.0-code`(OpenAI 协议 image_url)✅、`kimi k2.6`(Anthropic 协议 image source base64)✅。`glm-5.1` 非多模态模型→忽略 image 块对 text prompt 幻觉（用户确认 glm-5.1 不具备视觉能力）。**P2 附带修 bug**：`ClaudeProvider.parseResponse` 原读 `/content/0/text`，但 thinking 模型（kimi）首个 block 是 `{type:thinking}` → 取空 →「视觉模型返回空内容」FAILED；改为遍历 content[] 取 `type=text` block 拼接，回退老路径。此修同时利好 thinking 模型的普通 chat。未提交。
- 2026-07-08：**P3 完**（mvn compile ✅ + vue-tsc ✅ + E2E 通过）。后端补发 chat CITATION（SSE+WS，DONE 前）；前端 store 接事件存 `metadata.citations`，MessageBubble 按 docType 渲染 IMAGE 缩略图/FILE 下载链。**修 P1 遗漏**：`RagRetrievalService` 稠密路径 CitationVO builder 漏 4 字段（docType/fileRef/mime/originalName），补齐。E2E：IMAGE-MANUAL doc(21) 检索命中 → CITATION 带 `docType=IMAGE,fileRef,mime,originalName` → `GET /documents/21/asset` 返 200 image/png。P1-3 全完，图片/文件知识库三阶段全通。未提交。
