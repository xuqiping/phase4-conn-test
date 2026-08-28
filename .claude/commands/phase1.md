---
description: Phase1 规格沉淀｜商业报告→PRD+架构+数据模型+测试/安全/性能规格（specs before code）
argument-hint: [功能名或需求焦点]
---

# Phase 1 · 规格沉淀（specs before code）

你是规格架构师。把 Phase 0 商业报告转成**人与 AI 都对齐、跨会话不丢**的规格文档——没有规格，不写代码。以原文为唯一权威，完整执行其「动作清单」，产出其「产物」，满足其「出口条件」：
→ `2_编程类可迭代workflow/Phase1_规格沉淀.md`

`$ARGUMENTS` 作为本功能名 / 需求焦点（若为空，问用户或基于 Phase 0 报告定位）。

## 输入

- Phase 0 的 `项目分析报告.md`
- 技术选型、AGENTS.md 初版

## 必做（原文动作清单）

1. **Brainstorm 规格**：把想法 + 报告喂给 AI，要求它**反问直到挖清需求和边界**（不要让它直接写），逐一回答。
2. **「15 分钟瀑布」**：基于问答用**推理强模型**生成规格，**迭代到连贯完整**——边读边让它 critique / refine，直到没有断点。
3. **落盘规格**（至少要有 PRD，按需拆分）：
   - `PRD.md` —— 需求 / 用户故事 / 功能边界 / 非功能需求（含性能目标）
   - **PRD 必含「验收标准」**：每条需求 `FR-xxx` 至少 1 条 `AC-xxx`，用 **EARS**（`WHEN [条件] THE SYSTEM SHALL [行为]`）或 **Given/When/Then** 写，全文统一；每条 AC **可独立测试**并标验证方式（自动化 / 人工）
   - `architecture.md` —— 架构 / 模块 / 技术栈落点 + **§4.1 内部 API 契约简表**（路径 / 入参 / 出参 / 错误码，每条回链 FR）
   - `db_schema.md` —— 全局数据库设计（涉存储才产）：全局 ER 图（mermaid）+ 逐表数据字典（字段中文说明 / 类型 / 约束 / 枚举含义）+ Flyway 版本清单
   - `testing_strategy.md` —— 测试金字塔 + AC 映射表 + 标记需人工测试的功能 + 桌面端策略（PyQt/Tauri/Electron）
   - `security_strategy.md` —— 鉴权 / 加密 / 审计 / 漏洞管理
   - `performance_goals.md` —— 关键接口响应时间 / 并发量 / 资源使用率
4. **专业术语批注**：首次出现行内括注一句大白话，底部维护术语表（术语 | 大白话 | 案例）。主文专业度不变，批注只作辅助。
5. **写 `file_structure.md`**：告诉 AI 每个目录干嘛（Context Engineering 核心产物）。
6. **关键架构决策落 ADR**：选型 / 结构级关键决策在 `docs/adr/ADR-<n>-<标题>.md` 各记一篇（背景 / 备选 / 决定 / 理由 / 代价）+ `adr/README.md` 索引。
7. **定 API 契约**：architecture §4.1 填内部接口简表（每条回链 FR）；**对外开放接口**分流 `docs/api/<模块>.md`（鉴权 / 限流 / 版本 / 完整字段 / 示例 / 错误码 / 变更日志）。

## 产物

- `workflow_output/docs/specs/{PRD,architecture,db_schema,testing_strategy,security_strategy,performance_goals}.md`
- `workflow_output/docs/file_structure.md`
- （按需）`workflow_output/docs/adr/` + `workflow_output/docs/api/`

## 避坑（原文）

- **不要让 AI 一次写完规格就走**——价值在"迭代到连贯"这一步。
- 规格要含**测试策略 + 安全策略**（不是事后补丁）。
- **验收标准别写不可测的话**（"响应要快"不是 AC）——写不出来的 AC = 需求没想清。
- 规格是**唯一真相源**：后续冲突要么改实现要么改规格（并记录为什么改）。

## 出口条件（满足才进 Phase 2）

- [ ] PRD 经迭代确认，"要建什么、不建什么"清楚。
- [ ] 每条 `FR-xxx` 都有至少 1 条可测 `AC-xxx`（EARS/GWT）并标验证方式。
- [ ] 数据模型 / 架构 / 测试 / 安全 / 性能目标已覆盖或确认不需要；涉存储的 `db_schema.md` 已建初版。
- [ ] `file_structure.md` 已写。
- [ ] API 契约已定（architecture §4.1 内部；对外接口已分流 `docs/api/`）。

## 配套

- 权威原文：`2_编程类可迭代workflow/Phase1_规格沉淀.md`
- 6 份 specs 模板：`2_编程类可迭代workflow/项目模板/workflow_output/docs/specs/`
- 下一步：`/phase2`
