# Office Excel 拆分与合并实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: `phase3-implement`。依赖统一安全底座和 Office Pro 计划。

**目标：** 支持 `.xlsx/.csv/.xls/.xlsm` 的安全拆分、字段映射合并、公式策略和 VBA 受控合并。

---

### Chunk 1：Excel 扫描模型与失败测试

- [ ] **目标：先锁定工作簿结构、公式和宏风险。**
  - 动作：定义 Sheet/列/表头/公式/外链/VBA 描述；加入同名兼容、同名冲突、疑似映射和孤立列测试。
  - 涉及文件：`src/types/officeExcel.ts`、`src-tauri/src/office/excel/types.rs`、`scanner.rs`、`tests/excel_scanner_tests.rs`、`src-tauri/tests/fixtures/office/excel/`。
  - 依赖：安全底座扫描接口。
  - 伪代码：`scan workbook -> classify format -> inventory sheets/columns/formulas/links/vba -> issues`。
  - 验证：不打开宏执行；`.xlsm` 宏/签名/引用风险完整显示。

### Chunk 2：拆分规则与预览

- [ ] **目标：按 Sheet、列值、固定行数产生确定性输出计划。**
  - 动作：实现规则校验、表头、命名模板、重名策略、公式保留/转值和引用警告；制作可访问配置 UI。
  - 涉及文件：`src-tauri/src/office/excel/split_plan.rs`、`tests/excel_split_plan_tests.rs`、`src/components/office/excel/ExcelSplitWizard.vue`、`ExcelFormulaPolicy.vue`、`src/stores/officeExcelStore.ts`、语言包。
  - 依赖：Chunk 1。
  - 伪代码：`group rows -> sanitize output name -> detect reference impact -> preview outputs`。
  - 验证：空值、非法文件名、重复列值、0 行、跨 Sheet 公式、转值选择均有明确结果。

### Chunk 3：OOXML/CSV 拆分 Worker

- [ ] **目标：标准安全样本无需 Office 即可拆分。**
  - 动作：实现 ZIP/XML 部件透传、目标 Sheet 重写、sharedStrings/styles 复用、CSV 编码/分隔符检测；复杂样本路由 Office Worker。
  - 涉及文件：`src-tauri/src/bin/office_ooxml_worker.rs`、`src-tauri/src/office/excel/ooxml_split.rs`、`csv_split.rs`、Worker 契约测试、样本测试。
  - 依赖：技术尖峰、Chunk 2。
  - 伪代码：`copy package unchanged -> rewrite only selected worksheet parts -> validate relationships/invariants`。
  - 验证：公式/样式/宏附件不被意外丢弃；不支持结构明确返回 HIGH_FIDELITY_REQUIRED。

### Chunk 4：字段映射与合并预览

- [ ] **目标：所有列差异先说明、再选择。**
  - 动作：实现严格同名匹配、类型兼容评分、疑似映射建议、仅同名/追加不同名/手工映射模式；支持来源文件列。
  - 涉及文件：`src-tauri/src/office/excel/merge_plan.rs`、`column_matcher.rs`、测试、`src/components/office/excel/ExcelColumnMapping.vue`、`ExcelMergeWizard.vue`、store/语言包。
  - 依赖：Chunk 1。
  - 伪代码：`normalize header without silently merging -> build candidate graph -> user resolves every ambiguous edge`。
  - 验证：撤销映射重新阻断；切换模式后列集合与顺序正确；键盘可完成映射。

### Chunk 5：合并 Worker 与宏受控合并

- [ ] **目标：生成单一结果，失败不发布。**
  - 动作：实现 OOXML/CSV 安全合并；Windows Worker 处理 `.xls/.xlsm`、公式计算和 VBA 工程导入；预检密码、签名、引用、模块和过程重名。
  - 涉及文件：`src-tauri/src/office/excel/ooxml_merge.rs`、`tools/office-worker-windows/Excel/ExcelMergeService.cs`、`Vba/VbaPreflightService.cs`、`Vba/VbaMergeService.cs`、C# 测试项目、Rust 契约测试。
  - 依赖：Chunk 4、Windows Worker 基础。
  - 伪代码：`preflight all VBA -> require resolution map -> open Office with macros disabled -> import modules -> save temp -> reopen validate`。
  - 验证：未解决冲突/密码/Trust Center 限制阻止执行；数字签名失效报告；Office 宏不自动运行。

### Chunk 6：集成、性能与文档

- [ ] 接入任务历史、Pro 额度和 AI 映射入口；对 100 文件免费边界与 2,000 文件工程基准做流式测试；更新 Excel Feature Map、手册、人工测试方案并提交存档点。

## 重点坑点

- 表格/命名区域/合并单元格引用在拆分后可能失效：预检未能证明安全时路由 Office Worker或阻断。
- `.xls` 行列限制低于 `.xlsx`：输出超限必须提示转换格式，不可静默截断。
- 多 VBA 工程不存在通用自动合并：所有符号冲突均需用户明确解决。
- 类型推断不得只看首行；采样多个非空值并允许用户覆盖。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| sharedStrings | Excel 集中保存重复文本的表 | 单元格引用字符串编号 |
| 引用图 | 公式/名称/链接之间的依赖关系 | 拆走一行可能影响汇总公式 |
