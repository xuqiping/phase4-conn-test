# Office PowerPoint 合并与外链实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: `phase3-implement`。

**目标：** 支持 `.pptx/.pptm/.ppt` 合并、来源/目标主题策略、Excel 外链候选换源和 VBA 受控合并。

---

### Chunk 1：演示文稿预检模型

- [ ] **目标：扫描幻灯片、母版、字体、链接、嵌入对象和宏。**
  - 动作：定义 deck/slide/master/layout/font/link/VBA 描述及风险码；准备多母版、缺字体、外链和 `.pptm` 样本。
  - 涉及文件：`src/types/officePowerPoint.ts`、`src-tauri/src/office/powerpoint/types.rs`、`scanner.rs`、`tests/ppt_scanner_tests.rs`、PPT fixtures。
  - 依赖：安全底座。
  - 伪代码：`inventory each deck -> distinguish external link vs embedded object -> collect theme/master/font/vba issues`。
  - 验证：嵌入对象不进入换源列表；多候选/缺失链接为阻断或待确认。

### Chunk 2：合并顺序与主题预览

- [ ] **目标：用户可调整文件/幻灯片顺序并选择格式策略。**
  - 动作：实现拖拽排序、保留来源格式/应用目标主题、母版增量和字体冲突预览；全键盘提供上移/下移替代。
  - 涉及文件：`src-tauri/src/office/powerpoint/merge_plan.rs`、测试、`src/components/office/powerpoint/PptMergeWizard.vue`、`PptSlideOrder.vue`、`PptThemeConflictList.vue`、store/语言包。
  - 依赖：Chunk 1。
  - 伪代码：`ordered slide refs + theme strategy -> expected masters/layouts/fonts -> issues`。
  - 验证：反向切换主题策略重新计算问题；空 deck、隐藏 slide、重复 slide 均有明确规则。

### Chunk 3：外链候选与确认

- [ ] **目标：自动建议路径但绝不自动决定歧义。**
  - 动作：在用户指定目录按文件名、扩展名、相对路径和指纹评分；展示旧路径、候选、置信理由；逐组确认。
  - 涉及文件：`src-tauri/src/office/powerpoint/link_matcher.rs`、测试、`src/components/office/powerpoint/PptLinkMapping.vue`、store/语言包。
  - 依赖：Chunk 1。
  - 伪代码：`candidate score -> if unique high confidence then suggest, never auto-accept; else unresolved`。
  - 验证：多候选、大小写差异、同名不同扩展名、文件缺失；撤销映射重新阻断刷新。

### Chunk 4：Windows PowerPoint Worker

- [ ] **目标：高保真合并、换源、刷新和临时保存。**
  - 动作：创建独立 PowerPoint 实例；按顺序插入；应用主题策略；更新已确认链接；受控合并 VBA；保存并重开校验。
  - 涉及文件：`tools/office-worker-windows/PowerPoint/PptMergeService.cs`、`PptLinkService.cs`、`PptThemeService.cs`、共用 `Vba/*`、C# 测试、Rust 契约测试。
  - 依赖：Chunk 2、3、Windows Worker 基础。
  - 伪代码：`open copies with macros disabled -> insert slides -> resolve theme -> update confirmed links -> merge VBA -> save temp -> reopen`。
  - 验证：未确认链接不改；签名失效警告；失败不发布；用户原有 PowerPoint 实例不受影响。

### Chunk 5：结果比较与报告

- [ ] **目标：交付前发现页面数、链接和母版异常。**
  - 动作：比较预期/实际幻灯片数、顺序、外链目标、母版数和字体问题；生成断链报告和可重试任务。
  - 涉及文件：`src-tauri/src/office/powerpoint/validator.rs`、`report.rs`、`src/components/office/powerpoint/PptResultReport.vue`、测试。
  - 依赖：Chunk 4。
  - 验证：任何关键不变量不符则单输出回滚；未刷新链接明确列出。

### Chunk 6：人工验证与文档

- [ ] 使用 PowerPoint 肉眼检查主题、母版、字体、动画、图表、外链和宏；运行测试；更新 Feature Map、手册、测试方案并提交存档点。

## 重点坑点

- PowerPoint 插入幻灯片可能复制大量母版导致文件膨胀；预检估算并允许用户统一主题。
- 链接类型 API 行为不一致；必须区分 OLE、图表、媒体、超链接和嵌入对象。
- COM 刷新可能显示提示框或耗时；禁用交互提示、设置超时并把未刷新项写报告。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 母版 | 控制一组幻灯片公共版式的模板 | 公司标题、页脚、字体 |
| OLE 链接 | PPT 指向外部 Excel 对象的连接 | 图表随 Excel 更新 |
