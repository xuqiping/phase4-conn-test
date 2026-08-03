# Phase 1 · 规格沉淀（specs before code）

> 类型：流程步骤 · **Phase 1**
> 版本：v1.6（v1.1 增安全策略规格；v1.2 增性能目标定义；v1.3 增验收标准 section（FR/AC 编号 + EARS/GWT 写法）+ db_schema.md 全局数据库设计文档定位；v1.4 补齐 6 份 specs 模板骨架；v1.5 关键架构决策落 ADR；**v1.6 architecture §4 定 API 契约（路径/入参/出参/错误码），对外接口分流 docs/api/**）
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
   - **【v2.0】PRD 必含「验收标准」section**：功能需求编号 `FR-xxx`，每条需求至少 1 条验收标准 `AC-xxx`，用 **EARS 语法**（`WHEN [条件] THE SYSTEM SHALL [行为]` / `WHILE` / `IF-THEN` / `WHERE`）或 **Given/When/Then** 写，全文统一；每条 AC 必须**可独立测试**，并标注验证方式（自动化测试 / 人工验收）。功能联动类需求用 IF-THEN/WHEN 句式，正反向都写。
   - `workflow_output/docs/specs/architecture.md` —— 系统架构、模块划分、技术栈落点
   - `workflow_output/docs/specs/db_schema.md` —— **全局数据库设计文档**（若涉及存储）：全局 ER 图（mermaid erDiagram）+ 逐表数据字典（字段中文说明/类型/约束/默认值/枚举取值含义）+ 表关联说明 + Flyway 版本清单。它是数据库的**全局权威版**，Feature Map 的建表注解是本功能视角的速查版，两者分工不重复。
   - `workflow_output/docs/specs/testing_strategy.md` —— 测试策略（哪些要单测/集成/E2E/性能测试），并**标记哪些功能需要人工交互测试**（Phase 3 据此决定是否产 `workflow_output/docs/测试方案/`）；**若涉及桌面客户端，需明确桌面端技术栈与自动化测试策略（PyQt/Tauri/Electron 等）**
   - `workflow_output/docs/specs/security_strategy.md` —— **安全策略**（鉴权体系、加密方案、审计日志、漏洞管理、依赖安全等）；若项目较小，也可合并进 PRD 非功能需求。
   - `workflow_output/docs/specs/performance_goals.md` —— **【新增】性能目标**（关键接口响应时间、并发量支持、资源使用率限制等）；若项目较小，也可合并进 PRD 非功能需求。
4. **专业术语批注（保持专业度不变）**：规格里出现的专业术语，**首次出现时行内括注一句大白话**（如「**SSR**（服务端渲染——网页在服务器先拼好再发给浏览器，首屏快）」），并在**文档底部维护术语表**（术语 \| 大白话 \| 简单案例）。主文术语原样保留，批注只作辅助层。PRD / plan 模板都已内置术语表 section。
5. **写 file_structure.md**：把规划中的目录结构写清楚，告诉 AI 每个目录干嘛（这是 Context Engineering 的核心产物）。
6. **【v1.5】关键架构决策落 ADR**：选型/结构级的关键决策（为什么选 WebSocket 不选 SSE、为什么单体不微服务），在 `workflow_output/docs/adr/ADR-<n>-<标题>.md` 各记一篇（骨架见 `项目模板/workflow_output/docs/adr/_模板.ADR.md`：背景/备选方案/决定/理由/后果代价），并在 `adr/README.md` 索引登记。architecture.md 的「关键技术决策」表回链 ADR 编号。
7. **【v1.6】定 API 契约**：在 `architecture.md` §4.1 填**内部接口简表**（路径 / 方法 / 入参 / 出参 / 错误码，每条回链 FR）——Phase 3 照此实现，契约不清是前后端联调返工主因。**对外开放的接口**单独产 `workflow_output/docs/api/<模块>.md`（骨架见 `项目模板/workflow_output/docs/api/_模板.API文档.md`：含鉴权 / 限流 / 版本策略 / 完整字段 / 示例 / 错误码表 / 变更日志）。对外 API 即契约，变更必须走 Phase 6。

## 产物

- `workflow_output/docs/specs/PRD.md`（必备）+ architecture / db_schema / testing_strategy / security_strategy / **performance_goals**（按项目需要）
- `workflow_output/docs/file_structure.md`（必备）

## 配套

- 规格骨架 → `项目模板/workflow_output/docs/specs/PRD.md`（含验收标准 section）
- 架构骨架 → `项目模板/workflow_output/docs/specs/architecture.md`
- 数据库设计骨架 → `项目模板/workflow_output/docs/specs/db_schema.md`
- 测试策略骨架 → `项目模板/workflow_output/docs/specs/testing_strategy.md`
- 安全策略骨架 → `项目模板/workflow_output/docs/specs/security_strategy.md`
- 性能目标骨架 → `项目模板/workflow_output/docs/specs/performance_goals.md`
- 目录结构骨架 → `项目模板/workflow_output/docs/file_structure.md`

> 6 份 specs 模板均含固定 section 骨架 + 术语表 + 出口自查清单；小项目可只产 PRD 并把安全/性能并入其非功能需求（见裁剪表 L1），但正式产品（L2+）建议全产。

## 避坑点

- **不要让 AI 一次写完规格就走**。规格的价值在「迭代到连贯」这一步——第一次产出一定有断点，要逼它 critique 自己。
- 规格里要含**测试策略**。没有测试策略，Phase 4 会抓瞎，AI 也会「自信地交付坏代码」。
- 规格里要含**安全策略**。安全不是事后补丁，是前置设计。
- 规格是**唯一真相源**。后续 Phase 2/3 凡是和规格冲突的实现，要么改实现，要么改规格（并记录为什么改）。
- **【v1.3】验收标准别写不可测的话**。「响应要快」「体验要好」不是 AC——Phase 4 没法判通过/不通过，AI 实现时也会自由发挥。写不出来的 AC = 需求本身没想清，回炉重想。

## 出口条件（满足才进 Phase 2）

- [ ] PRD 经迭代确认，你和 AI 都清楚「要建什么、不建什么」。
- [ ] **【v2.0】每条功能需求（FR-xxx）都有至少 1 条可独立测试的验收标准（AC-xxx，EARS/GWT 写法），并标注了验证方式**。
- [ ] 数据模型、架构、测试策略、安全策略、**性能目标**已覆盖（或确认本项目不需要）。
- [ ] 涉及存储的，`db_schema.md` 已建初版（全局 ER 图 + 数据字典骨架）。
- [ ] file_structure.md 已写，目录骨架已建。
- [ ] **【v1.6】API 契约已定**：architecture §4.1 内部接口简表已填（每条回链 FR）；对外接口已分流到 `docs/api/`（若有）。
