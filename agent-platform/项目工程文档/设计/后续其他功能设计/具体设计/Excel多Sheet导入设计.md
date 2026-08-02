# Excel 多 Sheet 导入设计（POI 方案）

> 状态：设计稿（待评审）　·　归属模块：knowledge（知识库 RAG v6）
> 关联速查表：[14-知识库RAG-基础](../../../项目功能介绍/速查表/14-知识库RAG-基础.md)、[21-系统设置](../../../项目功能介绍/速查表/21-系统设置.md)
> 决策日期：2026-06-28

---

## 1. 背景与目标

### 1.1 现状缺口

知识库文档解析当前全走 Apache Tika（[DocumentParserService.java:132](../../../backend/src/main/java/com/superprogrammer/knowledge/service/DocumentParserService.java#L132) `Tika.parseToString`），对 Excel 存在三个硬伤：

1. **前端压根不让传 Excel** — [DocumentManager.vue:9](../../../frontend/src/components/knowledge/DocumentManager.vue#L9)
   `accept=".md,.txt,.markdown,.pdf,.docx,.doc,.html"`，xlsx/xls 连上传口都没有。所谓「Tika 支持 xlsx」从未端到端打通。
2. **无 sheet 维度** — Tika 把整个工作簿拍扁成一段纯文本，所有 sheet 首尾拼接，sheet 名边界丢失，无法「只导某 1 个 sheet」。
3. **表格结构散架** — Tika 行内 cell 拼字符串，列对齐/表头语义全丢，喂给 LLM 摘要基本糊掉。

### 1.2 目标

- 支持 `.xlsx` / `.xls` 上传，**指定 sheet 或多 sheet** 导入。
- 保住表格列结构（markdown 表），宽表/超大单元格自动降级。
- 改动最小：纯 Java，复用现有异步解析管线，不引 Python、不起微服务。

### 1.3 非目标

- 不做合并单元格高保真还原（v1 忽略合并，取 cell 原值）。
- 不做 Excel 公式重算（取缓存值，无缓存取字符串）。
- 不做跨 sheet JOIN / 关系建模。

---

## 2. 决策记录

| # | 决策点 | 选定 | 否决项 & 原因 |
|---|---|---|---|
| D1 | 解析引擎 | **Apache POI**（Java，复用 Tika 传递依赖） | pandas+Python sidecar：IPC 开销 + 污染 runtime-sidecar（默认 mock 不起 Python）+ 数据契约/部署成本，改动反而更多 |
| D2 | sheet 选择交互 | **可选 picker**：选完文件后端预读 sheet 名 → 勾选 → 确认上传 | 全量导入（多 sheet 大文件全灌）；单 sheetName 参数（体验差） |
| D3 | 多 sheet 落库粒度 | **1 文档多 Section**：1 Excel = 1 条 `knowledge_document`，每个 sheet = 一组 Section（title=`Sheet:销售`） | 1 sheet 1 文档：N 次解析 N 次 LLM 摘要，成本翻倍，列表视图被打散 |
| D4 | 表格序列化格式 | **markdown 表 + 宽表兜底**；列数/单元格阈值**用户可配** | 纯 markdown（宽表撑爆 token）；纯行流（列关系丢） |
| D5 | 文件生命周期 | **parse 完即删原件**：文档到 INDEXED 删磁盘字节，知识只留 nodes + 向量 | 保留原件（PII 明文躺磁盘 + 无下载功能 + 无用） |
| D6 | 存储安全 | **存储层归属校验**（最安全，不计改动量）：新增 `stored_files` 表记 owner，`load(fileId, userId)` 强校验 | 纯 Redis TTL（治标）；JWT-only（证身份≠证归属，挡不住 IDOR） |
| D7 | 事后改 sheet 范围 | **v1 不支持**（删了重传） | 重选 sheet 入口（需永久留原件，与 D5 冲突） |

---

## 3. 整体流程

两阶段上传（picker 必需，避免双传文件）：

```
[选文件 .xlsx]
   │
   ▼
POST /api/knowledge/documents/sheets/preview   ← 阶段1：预读 sheet 名
   │  · 存文件（FileStorageService.store）
   │  · POI 只读 sheet 名（不读行），立即 close
   │  · 不建 knowledge_document 行
   ▼
返回 { tempFileRef, fileName, sheetNames:[...] }
   │
   ▼
[前端弹 sheet 勾选框]  ── 用户勾选 ──▶ [确认上传]
   │
   ▼
POST /api/knowledge/documents/upload            ← 阶段2：确认上传
   │  · tempFileRef（复用阶段1存的文件，不重传）
   │  · selectedSheets=[...]（空=全部）
   │  · 落 knowledge_document.parse_options（JSON）
   │  · 发 DocumentUploadedEvent(docId)        ← 现有异步触发，不变
   ▼
[DocumentParseListener AFTER_COMMIT]
   │
   ▼
DocumentParserService.parse(docId)              ← 异步，按 docId 重查
   │  · extract() 分流：isExcel → POI；否则 → Tika（现状）
   │  · 按 parse_options.selectedSheets 取 sheet
   │  · 每行 → markdown 表，超阈值 → 行流兜底
   │  · 每 sheet → 若干 Section（title=Sheet:X，分块防超大）
   ▼
KnowledgeNodeWriter.writeNodes(...)             ← 现有落库，不变
   │  · Section → knowledge_nodes(ACTIVE) + index_jobs(PENDING)
   ▼
IndexJobWorker 异步 embed                        ← 现有，不变
```

**关键点**：阶段 1 存的文件通过 `tempFileRef` 复用，阶段 2 不重传 —— 单次上传流量，picker 交互零额外带宽。

---

## 4. 后端改造

### 4.1 依赖（pom.xml）

POI 5.2.x 已被 `tika-parsers-standard-package`（2.9.2）传递引入，类已在 classpath。**显式 pin 以防 Tika 升级时 POI 版本漂移**：

```xml
<poi.version>5.2.5</poi.version>
...
<!-- POI：Excel sheet 级解析（Tika 2.9.2 已传递引入 5.2.x，显式 pin 防漂移） -->
<dependency>
    <groupId>org.apache.poi</groupId>
    <artifactId>poi-ooxml</artifactId>
    <version>${poi.version}</version>
</dependency>
```

> 不移除 Tika —— pdf/docx/pptx/html 仍走 Tika。

### 4.2 数据库迁移（Flyway）

`knowledge_documents` 加一列存 sheet 选择。与现有 `l1_metadata`（String/TEXT 存 JSON）模式一致，**不引入 JSONB/TypeHandler**：

`V39__add_parse_options_to_knowledge_documents.sql`
```sql
ALTER TABLE knowledge_documents ADD COLUMN parse_options TEXT;
COMMENT ON COLUMN knowledge_documents.parse_options IS '解析选项 JSON（Excel sheet 选择等）。空=默认行为。';
```

`parse_options` JSON 结构：
```json
{ "selectedSheets": ["销售表", "库存表"] }   // null/空数组 = 导入全部 sheet
```

实体加字段 [KnowledgeDocument.java](../../../backend/src/main/java/com/superprogrammer/knowledge/entity/KnowledgeDocument.java)：
```java
/** 解析选项 JSON（Excel sheet 选择等）。空=默认行为。 */
private String parseOptions;
```

### 4.3 Controller（[KnowledgeDocumentController.java](../../../backend/src/main/java/com/superprogrammer/knowledge/controller/KnowledgeDocumentController.java)）

新增预读端点 + 改造上传端点：

```java
/** 阶段1：预读 Excel sheet 名。不建文档行，返回临时文件引用供阶段2复用。 */
@PostMapping("/sheets/preview")
@RequirePermission("knowledge:write")
public ResponseEntity<R<SheetPreviewVO>> previewSheets(@RequestParam("kbId") Long kbId,
                                                       @RequestParam("file") MultipartFile file) {
    return ResponseEntity.ok(R.ok(knowledgeDocumentService.previewSheets(
            kbId, file, getCurrentUserId(), isAdmin())));
}

/** 阶段2：上传（Excel 可带 tempFileRef + selectedSheets；其他类型走原路径）。 */
@PostMapping("/upload")
@RequirePermission("knowledge:write")
public ResponseEntity<R<KnowledgeDocumentVO>> upload(
        @RequestParam("kbId") Long kbId,
        @RequestParam(value = "file", required = false) MultipartFile file,
        @RequestParam(value = "tempFileRef", required = false) String tempFileRef,
        @RequestParam(value = "selectedSheets", required = false) List<String> selectedSheets) {
    return ResponseEntity.ok(R.ok("上传成功",
            knowledgeDocumentService.upload(kbId, file, tempFileRef, selectedSheets,
                    getCurrentUserId(), isAdmin())));
}
```

**向后兼容**：`file` 仍可单独传（非 Excel 或不用 picker 的场景），`tempFileRef`/`selectedSheets` 可选。

### 4.4 Service（[KnowledgeDocumentService.java](../../../backend/src/main/java/com/superprogrammer/knowledge/service/KnowledgeDocumentService.java)）

```java
// 阶段1：存文件 + POI 读 sheet 名
public SheetPreviewVO previewSheets(Long kbId, MultipartFile file, Long operatorId, boolean admin) {
    // 权限/空校验同 upload
    ensureWrite(kbId, operatorId, admin);
    StoredFile stored = fileStorageService.store(file);
    List<String> names = excelExtractor.sheetNames(stored.url());  // POI，只读名
    return SheetPreviewVO.builder()
            .tempFileRef(stored.url()).fileName(stored.name())
            .sheetNames(names).build();
}

// 阶段2：upload 加 tempFileRef + selectedSheets
@Transactional
public KnowledgeDocumentVO upload(Long kbId, MultipartFile file, String tempFileRef,
                                  List<String> selectedSheets, Long operatorId, boolean admin) {
    ensureWrite(kbId, operatorId, admin);
    // 复用 tempFileRef（picker 路径）或新存（直传路径）
    String fileRef = (tempFileRef != null && !tempFileRef.isBlank())
            ? tempFileRef : fileStorageService.store(file).url();
    if (tempFileRef == null && (file == null || file.isEmpty())) {
        throw new BusinessException(ErrorCode.BAD_REQUEST, "上传文件不能为空");
    }
    String fileHash = tempFileRef != null ? hashExisting(fileRef) : sha256(file);

    KnowledgeDocument doc = new KnowledgeDocument();
    doc.setKbId(kbId);
    doc.setTitle(tempFileRef != null ? deriveName(tempFileRef) : file.getOriginalFilename());
    doc.setStatus("PENDING");
    doc.setFileRef(fileRef);
    doc.setFileHash(fileHash);
    doc.setCreatedBy(operatorId);
    if (selectedSheets != null && !selectedSheets.isEmpty()) {
        doc.setParseOptions(objectMapper.writeValueAsString(
                Map.of("selectedSheets", selectedSheets)));
    }
    documentMapper.insert(doc);
    applicationEventPublisher.publishEvent(new DocumentUploadedEvent(doc.getId(), operatorId));
    return toVO(doc);
}
```

> `hashExisting`：tempFileRef 路径下文件已在存储里，重算 sha256 防与 `file_hash` 唯一约束/去重冲突。

### 4.5 解析器分流（[DocumentParserService.java](../../../backend/src/main/java/com/superprogrammer/knowledge/service/DocumentParserService.java)）

`extract()` 加 Excel 分支，其余文档不动：

```java
private ExtractedDocument extract(KnowledgeDocument doc) {
    return isExcel(doc) ? extractExcel(doc) : extractTika(doc);   // extractTika = 现 extract() 原样改名
}

private static boolean isExcel(KnowledgeDocument doc) {
    String ref = doc.getFileRef() == null ? "" : doc.getFileRef().toLowerCase();
    return ref.endsWith(".xlsx") || ref.endsWith(".xls");
}

private ExtractedDocument extractExcel(KnowledgeDocument doc) {
    Set<String> selected = readSelectedSheets(doc.getParseOptions());   // 空=全部
    return excelExtractor.extract(stripFileRef(doc.getFileRef()), selected);
}
```

新增组件 `ExcelSheetExtractor`（`knowledge/service/internal/`）：

```java
public ExtractedDocument extract(String fileId, Set<String> selected) {
    Resource res = fileStorageService.load(fileId);
    try (InputStream in = res.getInputStream();
         Workbook wb = WorkbookFactory.create(in)) {        // 自动识别 xlsx(XSSF)/xls(HSSF)
        List<Section> sections = new ArrayList<>();
        StringBuilder plain = new StringBuilder();
        int colThreshold = settingService.getExcelColThreshold();   // 默认 10
        int rowChunk    = settingService.getExcelRowChunkSize();    // 默认 200
        int cellMax     = settingService.getExcelCellMaxChars();    // 默认 200
        int maxRows     = settingService.getExcelMaxRowsPerSheet(); // 默认 5000，防超大表
        for (int i = 0; i < wb.getNumberOfSheets(); i++) {
            Sheet sh = wb.getSheetAt(i);
            if (sh.isHidden()) continue;                          // 跳过隐藏 sheet
            if (!selected.isEmpty() && !selected.contains(sh.getSheetName())) continue;
            List<Section> shSections = sheetToSections(sh, colThreshold, rowChunk, cellMax, maxRows);
            sections.addAll(shSections);
            shSections.forEach(s -> { plain.append(s.getTitle()).append('\n').append(s.getContent()).append("\n\n"); });
        }
        return ExtractedDocument.builder().plainText(plain.toString()).sections(sections).build();
    }
}
```

`sheetToSections` —— markdown 表 + 宽表兜底核心：

```java
private List<Section> sheetToSections(Sheet sh, int colThreshold, int rowChunk, int cellMax, int maxRows) {
    String sheetTitle = "Sheet:" + sh.getSheetName();
    int cols = firstRowCols(sh);
    boolean wide = cols > colThreshold;                // 宽表判定
    int total = Math.min(sh.getLastRowNum() + 1, maxRows);
    if (sh.getLastRowNum() + 1 > maxRows) {
        log.warn("sheet {} 行数 {} 超上限 {}，已截断", sh.getSheetName(), sh.getLastRowNum()+1, maxRows);
    }
    List<Section> out = new ArrayList<>();
    for (int from = 0; from < total; from += rowChunk) {
        int to = Math.min(from + rowChunk, total);
        StringBuilder body = wide
                ? renderRowStream(sh, from, to, cellMax)   // 宽表：第N行 A=.. B=..
                : renderMarkdownTable(sh, from, to, cellMax); // 正常：| col | col |
        if (body.length() == 0) continue;
        String title = total <= rowChunk ? sheetTitle : sheetTitle + " (行 " + (from+1) + "-" + to + ")";
        String c = body.toString();
        out.add(Section.builder().title(title).content(c).tokenCount(TokenEstimator.estimate(c)).build());
    }
    return out.isEmpty() ? List.of() : out;             // 空 sheet → 0 section
}
```

下游 `summarizePerSection/Batch/Hybrid` 直接吃 `Section` 列表，**完全复用，不动**。L1 文档级摘要覆盖整个 Excel，section 摘要按 `Sheet:X` 粒度。

### 4.6 阈值配置（[SystemSettingService.java](../../../backend/src/main/java/com/superprogrammer/system/service/SystemSettingService.java)）

沿用 `rag.memory.keyword-max` 同款 KV 模式（`system_settings` upsert，无 Flyway）：

| 键 | 默认 | 含义 |
|---|---|---|
| `knowledge.excel.col-threshold` | 10 | 列数 > 此值 → 行流兜底 |
| `knowledge.excel.row-chunk-size` | 200 | 每 Section 最大行数（防超大 section） |
| `knowledge.excel.cell-max-chars` | 200 | 单 cell 文本截断长度 |
| `knowledge.excel.max-rows-per-sheet` | 5000 | 单 sheet 行数硬上限（截断防 OOM） |
| `knowledge.excel.preview-max-sheets` | 50 | 预读端点返回 sheet 名上限（防恶意巨多 sheet 文件） |

加常量 + getter（非法/缺失回退默认，仿 [SystemSettingService:213-226](../../../backend/src/main/java/com/superprogrammer/system/service/SystemSettingService.java#L213) `getKeywordMax`）。

### 4.7 DTO

- `SheetPreviewVO { tempFileRef, fileName, sheetNames[] }`
- `KnowledgeDocumentVO` 加 `parseOptions`（可选，便于前端展示已选 sheet）。

---

## 5. 前端改造

### 5.1 文件类型（[DocumentManager.vue:9](../../../frontend/src/components/knowledge/DocumentManager.vue#L9)）

```diff
- accept=".md,.txt,.markdown,.pdf,.docx,.doc,.html"
+ accept=".md,.txt,.markdown,.pdf,.docx,.doc,.html,.xlsx,.xls"
```

### 5.2 Sheet picker 交互

`DocumentManager.vue` 上传回调分流：

```ts
async function handleFile(file) {
  const name = file.file.name.toLowerCase()
  if (name.endsWith('.xlsx') || name.endsWith('.xls')) {
    // Excel：先 preview → 弹勾选 → confirm upload
    const preview = await store.previewSheets(props.kbId, file.file)   // 阶段1
    const picked = await openSheetPicker(preview.sheetNames)           // NaiveUI modal + n-checkbox-group
    if (picked.length === 0) return                                     // 取消
    await store.uploadDocument(props.kbId, {
      tempFileRef: preview.tempFileRef, selectedSheets: picked          // 阶段2，复用 tempFileRef
    })
  } else {
    await store.uploadDocument(props.kbId, file.file)                   // 非 Excel，原路径
  }
}
```

新增组件 `components/knowledge/SheetPickerModal.vue`（n-modal + n-checkbox-group + 全选/反选）。

### 5.3 API（[knowledge.ts](../../../frontend/src/api/knowledge.ts) + [stores/knowledge.ts](../../../frontend/src/stores/knowledge.ts)）

```ts
export function previewSheets(kbId, file) {
  const form = new FormData()
  form.append('kbId', String(kbId)); form.append('file', file)
  return request.post('/api/knowledge/documents/sheets/preview', form)
}
export function uploadDocument(kbId, payload /* File | {tempFileRef, selectedSheets} */) { ... }
```

---

## 6. 边界与降级

| 场景 | 处理 |
|---|---|
| 空 sheet（0 行） | 跳过，0 section，不报错 |
| 隐藏 sheet | 默认跳过（`sheet.isHidden()`） |
| 密码保护 | `WorkbookFactory.create` 抛异常 → `parse()` 现有 wide-catch → `markFailed(FAILED + parse_error)` |
| 超大行数（>5000） | 按 `max-rows-per-sheet` 截断，`log.warn` 记录，不静默 |
| 宽表（列>10） | 自动转行流兜底 |
| 超长 cell | 按 `cell-max-chars` 截断，尾部加 `…` |
| 合并单元格 | v1 忽略合并，取 cell 原值（重复值可能出现）—— 列为已知限制，下版再补 |
| 公式 cell | 取 `getCellFormula` 缓存值（`getCachedFormulaResultValue`），无缓存取空 |
| 预读巨多 sheet | `preview-max-sheets` 截断返回列表 |
| 非 Excel | 走 Tika 原路径，零影响 |
| 老 `parse_options=NULL` 文档 | `readSelectedSheets` 返回空集 = 导全部 sheet |

---

## 7. 测试要点（IT，真文件 IO）

| 用例 | 断言 |
|---|---|
| 多 sheet xlsx（3 sheet） | 3 组 section，title 含 `Sheet:` 前缀 |
| selectedSheets 过滤 | 只含选中 sheet 的 section |
| 宽表（15 列） | section content 为行流格式，非 markdown 表 |
| 空 sheet | 该 sheet 0 section，其余正常 |
| 密码保护 xlsx | doc.status=FAILED，parse_error 非空 |
| .xls 老格式 | 与 .xlsx 同样产出 section（HSSF 路径） |
| 非 Excel（pdf） | 仍走 Tika，section 数/内容与改造前一致（回归） |
| preview 端点 | 返回 sheet 名列表，**不**建 knowledge_document 行 |

> H2 跑不了 halfvec/tsvector，但本特性纯解析层（Section → nodes 写入前），可单测 `ExcelSheetExtractor` + IT 验证 doc 落库 parse_options。

---

## 8. 风险

| 风险 | 等级 | 缓解 |
|---|---|---|
| 巨型 xlsx 解析 OOM | 中 | `max-rows-per-sheet` 截断 + `row-chunk-size` 分块；POI `WorkbookFactory` 默认全量加载，10w+ 行可考虑后续换 SAX 流式（`XSSFReader`），v1 先截断 |
| tempFileRef 被滥用（伪造引用他人文件） | 低（已治本） | **D6 存储层归属校验**根治：`load(fileId, userId)` 强校验 owner，用户 A 拿 B 的 fileRef 直接被拒。不再依赖会话/TTL 这类治标手段 |
| **既有 `GET /api/files/{id}` IDOR**（独立于本特性） | 高（既有） | 同一 `load` 咽喉点修复：FileController.get 改调 `load(fileId, currentUserId)`，补归属校验。**建议同期修**（见 §10 安全模型） |
| **既有 orphan 文件泄漏**（删文档不清磁盘） | 中（既有） | D5 + `FileStorageService.delete()`：文档删除 / INDEXED 后删字节。**同期修** |
| picker 双接口增加前端复杂度 | 低 | 非 Excel 走原路径，影响面隔离在 `handleFile` 分流 |
| POI 与 Tika POI 5.2.x 版本冲突 | 低 | 显式 pin `poi-ooxml 5.2.5`，与 Tika 2.9.2 传递的 5.2.x 同主线 |
| `parse_options` TEXT 存 JSON 无校验 | 低 | 写入侧 Service 序列化，读出侧 try-catch 容错（仿 l1Metadata） |

---

## 9. 落地清单（文件级）

**后端**
- [x] `backend/pom.xml` — pin `poi-ooxml 5.2.5`
- [x] `db/migration/V39__add_parse_options_to_knowledge_documents.sql` — 新列（`parse_options` + `parse_warning`）
- [x] `knowledge/entity/KnowledgeDocument.java` — `parseOptions` + `parseWarning` 字段
- [x] `knowledge/dto/SheetPreviewVO.java` — 新建
- [x] `knowledge/dto/KnowledgeDocumentVO.java` — 加 `parseOptions` + `parseWarning`
- [x] `knowledge/controller/KnowledgeDocumentController.java` — `previewSheets` + 改造 `upload`
- [x] `knowledge/service/KnowledgeDocumentService.java` — `previewSheets` + 改造 `upload`
- [x] `knowledge/service/internal/ExcelSheetExtractor.java` — 新建（POI 核心）
- [x] `knowledge/service/DocumentParserService.java` — `extract()` 分流 + `extractExcel`
- [x] `system/service/SystemSettingService.java` — 5 个 `knowledge.excel.*` 键 + getter

**安全与文件生命周期（D5/D6，治本同期修既有 IDOR + orphan 泄漏）**
- [x] `db/migration/V40__create_stored_files.sql` — 新表 `stored_files`（实际列集见迁移；deleted 由硬删行替代，未设软删列）
- [x] `file/entity/StoredFileEntity.java` + `StoredFileMapper` — 落库 owner
- [x] `file/service/FileStorageService.java` — `store(file, ownerId, source[, kbId, expiresAt])` 记 owner；`load(fileId, userId, admin)` **强校验归属**（mismatch 抛 FORBIDDEN，admin 可越权）；新增 `delete(fileId)`
- [x] `file/controller/FileController.java` — `get` 改调 `load(fileId, currentUserId, isAdmin)` → **修既有 IDOR**；`upload` 记 owner
- [x] `knowledge/service/KnowledgeDocumentService.java` — preview/upload 调 `store(file, userId, "KB")`；`delete()` 补 `fileStorageService.delete()` **修 orphan 泄漏**
- [x] `knowledge/service/IndexJobWorker.java` + `IndexJobTxService` + `FileStorageService` — 文档 INDEXED 后清原件字节 + `stored_files.status=CLEANED`（开关 `app.files.retain-after-index=false`，默认清）
　　· `IndexJobTxService.markDocIndexedIfDone` 转 INDEXED 时返回 doc.fileRef（仅转换瞬间非空，多 worker 并发下仅最后完成者触发）；`completeUpsert`/`completeUpsertL1` 由 void 改返回 fileRef 上抛
　　· `IndexJobWorker.cleanOriginalFileAfterIndex(fileRef)`：事务外 glue（文件 IO 不在 DB tx，删失败不回滚 INDEXED）→ `FileStorageService.cleanAfterIndex(fileId)` 删字节 + 置 CLEANED（**保留登记行**，区别于 doc 删除的 `delete()` 硬删）
　　· 知识完整性靠 `knowledge_nodes` + 向量，重嵌读 nodes 不依赖原件；清后 `load` → NOT_FOUND（预期）
　　· IT `IndexJobFileLifecycleIT` 2/2 绿（completeUpsert 返 fileRef + INDEXED / 清字节 + CLEANED 行保留）；`IndexJobWorkerTest` 10/10、`IndexJobTxServiceTest` 15/15 绿
- [x] 新增 `parse_warning` 字段：截断/降级时写「sheet X 行数 N > 上限 M，已截断」→ 持久化 + VO（前端黄色徽章 UI 待补）

**前端**
- [x] `components/knowledge/DocumentManager.vue` — accept 补 xlsx + `customUpload` 分流（Excel→preview→picker→confirm）
- [x] `components/knowledge/SheetPickerModal.vue` — 新建
- [x] `api/knowledge.ts` — `previewSheets` + `uploadDocumentSheets`
- [x] `stores/knowledge.ts` — 对应 action

**文档**
- [x] `速查表/14-知识库RAG-基础.md` — 解析节注明 Excel/POI 分流 + `parse_options` 列 + 文件归属咽喉点
- [x] `速查表/21-系统设置.md` — 加 `knowledge.excel.*` 配置键节

**测试**
- [x] `FileStorageOwnershipIT`（归属强制 e2e，4/4 绿）+ `ExcelSheetExtractorTest`（5/5 绿）+ `FileStorageServiceTest`（2/2 绿）
- [x] `ExcelDocumentParseIT`（真 PG + 真磁盘 + @MockBean LLM，7/7 绿）—— Phase 4 已落地
　　· 集成边界用例：preview 不建 doc 行（+ stored_files 记 PREVIEW owner）/ upload(tempFileRef) 落 parse_options / parse 多 sheet→每 sheet 一 L0 节点（Sheet:X）/ selectedSheets 过滤 / .xls 走 HSSF / .txt 走 Tika 回归（正文 round-trip；pdf/docx/html 同此 isExcel=false 分支）/ 加密 xlsx→markFailed(FAILED+parse_error)
　　· 纯解析层内部 mechanics（多 sheet section / 筛选 / 宽表行流 / 空隐藏跳过）已由 `ExcelSheetExtractorTest` 单测覆盖，IT 不重复
　　· 备注：fixture 文本须 `.getBytes(UTF_8)`——本机 JVM 默认 GBK，`.getBytes()` 产 GBK 字节使 Tika 返空（已踩）
- [x] `IndexJobFileLifecycleIT`（D5 文件生命周期，真 PG + 真磁盘，2/2 绿）+ `IndexJobWorkerTest`（10/10）+ `IndexJobTxServiceTest`（15/15）—— D5 已落地

---

## 10. 安全模型与文件生命周期（最终方案）

> 三条决策已定：D5 parse 完删原件 / D6 存储层归属校验（最安全，不计改动量）/ D7 v1 不支持事后改 sheet。本节为落地细则。

### 10.1 为什么 JWT 不够（审计结论）

`GET /api/files/{fileId}`（[FileController:31](../../../backend/src/main/java/com/superprogrammer/file/controller/FileController.java#L31)）现已存在 **authenticated IDOR**：
- 需登录（[SecurityConfig:49](../../../backend/src/main/java/com/superprogrammer/auth/security/SecurityConfig.java#L49)），但 `load(fileId)` **零归属校验**
- fileId = UUID（[FileStorageService:36](../../../backend/src/main/java/com/superprogrammer/file/service/FileStorageService.java#L36)），不可猜但会从 URL/日志/分享链泄露
- 任何登录用户 + 泄露 fileId = 读任何人文件

**根因**：`StoredFile` 无 owner，全系统不跟踪归属。JWT 证「你是谁」，证不了「这文件是你的」。Excel tempFileRef 会在同一根因上新开注入面。→ 必须在**存储层**补归属，单一咽喉点 `load`。

### 10.2 stored_files 表（V40 迁移）

```sql
CREATE TABLE stored_files (
    file_id        VARCHAR(128) PRIMARY KEY,      -- UUID+ext，即现有 fileId
    owner_user_id  BIGINT      NOT NULL,
    kb_id          BIGINT      NULL,              -- 来源知识库（KB 场景），便于按 KB 清理
    source         VARCHAR(16) NOT NULL,          -- KB / WORKFLOW / CHAT / PREVIEW
    status         VARCHAR(16) NOT NULL,          -- ACTIVE / CLEANED / EXPIRED
    original_name  VARCHAR(255),
    mime           VARCHAR(128),
    size           BIGINT,
    expires_at     TIMESTAMPTZ NULL,              -- PREVIEW 临时文件 TTL（如 10min）
    created_at     TIMESTAMPTZ NOT NULL,
    deleted        BOOLEAN     NOT NULL DEFAULT FALSE
);
CREATE INDEX idx_stored_files_owner ON stored_files(owner_user_id);
CREATE INDEX idx_stored_files_expires ON stored_files(expires_at) WHERE expires_at IS NOT NULL;
```

### 10.3 load 咽喉点（核心安全控制）

```java
/** 强校验归属。owner 不匹配抛 FORBIDDEN（admin 可越权读，便于运维）。 */
public Resource load(String fileId, Long userId, boolean admin) {
    StoredFileEntity meta = storedFileMapper.selectById(fileId);
    if (meta == null || meta.isDeleted()) throw notFound(fileId);
    if (!admin && !meta.getOwnerUserId().equals(userId)) {
        throw new BusinessException(ErrorCode.FORBIDDEN, "无权访问该文件");
    }
    return new FileSystemResource(resolveSafe(fileId));   // 现有路径校验保留
}
```

所有调用方改传 `userId`：
- `FileController.get` → `load(fileId, currentUserId, isAdmin)` → **修既有 IDOR**
- `DocumentParserService.extract` → `load(fileId, doc.getCreatedBy(), false)`（文档 owner 即文件 owner）
- Excel preview/upload 内部调用同理

`store` 签名扩为 `store(file, ownerId, source)`，写 `stored_files` 行 + 落盘（落盘逻辑不变）。

> **defense-in-depth（可选附加）**：fileRef 改为 `fileId + "." + HMAC(fileId)`，签名校验在 load 前。有了 owner 校验后此层冗余，但可防 fileId 拼写注入。视团队风险偏好决定是否加。

### 10.4 Excel tempFileRef 在此模型下的安全性

阶段1 preview：`store(file, currentUserId, "PREVIEW")` + `expires_at = now + 10min`。
阶段2 upload：传 tempFileRef → `load(tempFileId, currentUserId, false)`：
- 用户 A 传 B 的 fileId → owner mismatch → **FORBIDDEN**，注入死在咽喉点
- 用户 A 传自己的 → 通过；写 doc.parse_options；status 转 ACTIVE
- 超过 10min 未确认 → `expires_at` 过期，定时清理（见 10.5）

无需 Redis 会话表/TTL 方案——owner 校验是治本，TTL 仅用于清理过期预览文件。

### 10.5 文件生命周期（D5）

| 事件 | 动作 |
|---|---|
| store（preview/upload） | 落盘 + 写 `stored_files(ACTIVE)`；preview 带 `expires_at` |
| 文档 INDEXED（embed 全完成） | `fileStorageService.delete(fileId)` 删字节 + `status=CLEANED`（受 `app.files.retain-after-index=false` 控制；调试可设 true 保留） |
| 文档删除 | 删字节 + `stored_files` 行（**修既有 orphan 泄漏**） |
| 定时扫描 | 清 `expires_at < now` 的 PREVIEW 残留（@Scheduled，复用现有节拍模式） |

知识完整性：删原件后，`knowledge_nodes`（文本）+ `knowledge_embeddings`（向量）仍在，检索/问答不受影响。换 embedding 模型重算读 nodes，**不依赖原件**。

### 10.6 parse_warning（D-#2 决策）

文档表加 `parse_warning TEXT`（与 `parse_error` 并列，非致命）：
- sheet 行数超 `max-rows-per-sheet` 截断 → 写「sheet 销售 行数 12000 > 上限 5000，已截断」
- 宽表降级、cell 截断同理累积
- 前端文档列表：有 parse_warning → 黄色徽章 + tooltip；有 parse_error → 红色 FAILED
- 字段加进 `KnowledgeDocumentVO` + 迁移加列（V39 一并加 `parse_options` 时同加）
