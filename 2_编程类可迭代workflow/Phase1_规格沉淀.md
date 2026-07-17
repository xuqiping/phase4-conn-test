# Phase 1 · 规格沉淀（specs before code）

> 类型：流程步骤 · **Phase 1**
> 版本：v1.2（v1.1 增安全策略规格；v1.2 增性能目标定义）
> 对应铁律：**#1 规格先行** · 方法论锚点见 [0_方法论核心.md](0_方法论核心.md)

## 目标

把 Phase 0 的商业报告，转成一份**人和 AI 都能对齐、跨会话不丢**的规格文档。这是 SDD 的灵魂：**specs before code**——没有规格，不写代码。

## 输入

- Phase 0 的 `项目分析报告.md`
- 技术选型、总体规范（AGENTS.md 初版）

## 动作清单

1. **Brainstorm 规格**：把想法 + 报告喂给 AI，要求它**反问直到挖清需求和边界**（不要让它直接写）。逐一回答它的问题。
2. **「15 分钟瀑布」**：基于问答，用**推理强模型**生成规格，**迭代到连贯完整**——边读边让它 critique / refine，直到没有断点。
3. **落盘规格文档**（按需拆分，至少要有 PRD）：
   - `workflow_output/docs/specs/PRD.md` —— 需求、用户故事、功能边界、非功能需求（含**性能目标**）
   - `workflow_output/docs/specs/architecture.md` —— 系统架构、模块划分、技术栈落点
   - `workflow_output/docs/specs/db_schema.md` —— 数据模型（若涉及存储）
   - `workflow_output/docs/specs/testing_strategy.md` —— 测试策略（哪些要单测/集成/E2E/性能测试），并**标记哪些功能需要人工交互测试**（Phase 3 据此决定是否产 `workflow_output/docs/测试方案/`）；**若涉及桌面客户端，需明确桌面端技术栈与自动化测试策略（PyQt/Tauri/Electron 等）**
   - `workflow_output/docs/specs/security_strategy.md` —— **安全策略**（鉴权体系、加密方案、审计日志、漏洞管理、依赖安全等）；若项目较小，也可合并进 PRD 非功能需求。
   - `workflow_output/docs/specs/performance_goals.md` —— **【新增】性能目标**（关键接口响应时间、并发量支持、资源使用率限制等）；若项目较小，也可合并进 PRD 非功能需求。
4. **专业术语批注（保持专业度不变）**：规格里出现的专业术语，**首次出现时行内括注一句大白话**（如「**SSR**（服务端渲染——网页在服务器先拼好再发给浏览器，首屏快）」），并在**文档底部维护术语表**（术语 \| 大白话 \| 简单案例）。主文术语原样保留，批注只作辅助层。PRD / plan 模板都已内置术语表 section。
5. **写 file_structure.md**：把规划中的目录结构写清楚，告诉 AI 每个目录干嘛（这是 Context Engineering 的核心产物）。

## 产物

- `workflow_output/docs/specs/PRD.md`（必备）+ architecture / db_schema / testing_strategy / security_strategy / **performance_goals**（按项目需要）
- `workflow_output/docs/file_structure.md`（必备）

## 配套

- 规格骨架 → `项目模板/workflow_output/docs/specs/PRD.md`
- 目录结构骨架 → `项目模板/workflow_output/docs/file_structure.md`

## 避坑点

- **不要让 AI 一次写完规格就走**。规格的价值在「迭代到连贯」这一步——第一次产出一定有断点，要逼它 critique 自己。
- 规格里要含**测试策略**。没有测试策略，Phase 4 会抓瞎，AI 也会「自信地交付坏代码」。
- 规格里要含**安全策略**。安全不是事后补丁，是前置设计。
- 规格是**唯一真相源**。后续 Phase 2/3 凡是和规格冲突的实现，要么改实现，要么改规格（并记录为什么改）。

## 出口条件（满足才进 Phase 2）

- [ ] PRD 经迭代确认，你和 AI 都清楚「要建什么、不建什么」。
- [ ] 数据模型、架构、测试策略、安全策略、**性能目标**已覆盖（或确认本项目不需要）。
- [ ] file_structure.md 已写，目录骨架已建。
