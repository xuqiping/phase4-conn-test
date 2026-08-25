# Office Word 批量替换实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: `phase3-implement`。

**目标：** 跨多个 Word 文件替换正文、表格、页眉页脚、脚注尾注和文本框，并保证批注与修订记录不变。

---

### Chunk 1：范围扫描与规则模型

- [ ] **目标：让支持/排除范围机器可验证、用户可理解。**
  - 动作：定义 Story Range（Word 不同内容区域）清单、普通/大小写/全词/高级匹配规则、替换清单导入格式；编写批注/修订不变测试。
  - 涉及文件：`src/types/officeWord.ts`、`src-tauri/src/office/word/types.rs`、`scanner.rs`、`tests/word_scope_tests.rs`、Word fixtures。
  - 依赖：安全底座。
  - 伪代码：`inventory supported stories; mark comments/revisions EXCLUDED; parse rule list with stable ids`。
  - 验证：任务配置、预览和报告都出现排除说明；说明可被屏幕阅读器读取。

### Chunk 2：替换预览引擎

- [ ] **目标：执行前展示每条规则影响的文件、区域和数量。**
  - 动作：实现跨 XML run 匹配、上下文片段脱敏显示、逐条启停和冲突顺序；高级模式单独风险确认。
  - 涉及文件：`src-tauri/src/office/word/preview.rs`、`matcher.rs`、测试、`src/components/office/word/WordReplaceWizard.vue`、`WordReplacementPreview.vue`、`src/stores/officeWordStore.ts`、语言包。
  - 依赖：Chunk 1。
  - 伪代码：`flatten visible text with run map -> match -> map replacement back without touching excluded stories`。
  - 验证：跨 run 文本、重复规则、替换结果再次命中下一规则、空替换均有确定顺序。

### Chunk 3：OOXML 安全替换

- [ ] **目标：标准 `.docx` 在安全子集内本地处理。**
  - 动作：只重写目标 WordprocessingML 部件，保留其他 ZIP entries；处理页眉页脚、脚注尾注和文本框；不写 comments/track changes 部件。
  - 涉及文件：`src-tauri/src/office/word/ooxml_replace.rs`、OOXML Worker、契约测试、样本测试。
  - 依赖：技术尖峰、Chunk 2。
  - 伪代码：`copy package -> rewrite approved story parts -> compare excluded parts byte-for-byte -> validate open`。
  - 验证：批注、修订、样式和关系部件不变；复杂结构自动路由高保真 Worker。

### Chunk 4：旧格式/复杂 Word Worker

- [ ] **目标：通过已安装 Word 处理 `.doc`、密码和复杂文本框。**
  - 动作：Windows Worker 以禁宏方式打开副本，枚举支持 StoryRanges，执行替换，保存临时结果并重开校验。
  - 涉及文件：`tools/office-worker-windows/Word/WordReplaceService.cs`、`Word/WordPreflightService.cs`、C# 测试、Rust Worker 路由测试。
  - 依赖：Chunk 2、Windows Worker 基础。
  - 伪代码：`open copy read/write with automation security disabled -> replace supported stories -> verify excluded counts/hashes`。
  - 验证：自动宏不运行；密码错误不记录；文件占用和 Word 弹窗转换为明确错误。

### Chunk 5：部分成功与报告

- [ ] **目标：失败文件不影响已成功输出，并能精确重试。**
  - 动作：按文件原子发布；报告每规则/每区域命中数、跳过原因和排除范围；支持只重试失败项。
  - 涉及文件：`src-tauri/src/office/word/report.rs`、`src/components/office/word/WordResultReport.vue`、任务 store、测试。
  - 依赖：Chunk 3、4。
  - 验证：混合成功/失败为 partial_success；重试不重复覆盖成功输出。

### Chunk 6：人工验证与文档

- [ ] 使用真实 Word 检查各区域、修订和排除说明；运行测试；更新 Feature Map、手册、测试方案并提交存档点。

## 重点坑点

- Word 文本可能被样式拆成多个 run；不得用单节点字符串替换。
- 替换跨越不同格式时必须定义格式继承：默认采用首个匹配字符格式并在预览警告。
- Word COM 的 StoryRanges 可能有链式 NextStoryRange；必须遍历但去重，禁止遗漏或无限循环。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| Story Range | Word 中相互独立的文字区域 | 正文、页眉、脚注 |
| run | 一段具有相同格式的文字 | 一个词可能被拆成多个 run |
