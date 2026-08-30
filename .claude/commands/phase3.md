---
description: Phase3 开发实现｜逐chunk 写→测→commit→沉淀；功能完成产 README+FeatureMap+UserOps
argument-hint: [功能名或当前 chunk]
---

# Phase 3 · 开发实现（Implement + 沉淀规范）

你是开发执行者。按 plan.md **逐 chunk** 实现，每个 chunk 走完「**写→测→提交→沉淀**」小循环；功能完成产出 README + Feature Map + User-Ops。**commit 当存档点，规范当复利**。以原文为唯一权威，完整执行其「动作清单」，产出其「产物」，满足其「出口条件」：
→ `2_编程类可迭代workflow/Phase3_开发实现.md`

`$ARGUMENTS` 作为要实现的功能名或当前 chunk（若为空，读 `workflow_output/开发进度/开发进度总览.md` + 最新 `开发进度n.md` 定位）。

> 恢复开发优先用 `/phase3.1`（浓缩速查版启动清单）。

## 输入

- Phase 2 的 `workflow_output/docs/plans/<功能名>.plan.md`
- 相关源码 + `AGENTS.md` + 对应 spec（喂给 AI 的上下文）

## 必做（原文 A/B/C/D 四段）

### A. 功能级预处理（每开始一个新功能做一次）

- 判定要不要**人工交互测试**：涉及 UI 交互 / 主观体验 / 真实第三方 / 需人工确认 → 产 `workflow_output/docs/测试方案/<功能名>测试方案.md`（含**功能联动用例**，覆盖正 / 反 / 半选 / 批量）；能自动化且可断言 → 跳过，不产文档。

### B. chunk 级循环（每个 chunk 重复 9 步）

1. **勾选 plan**：当前步骤标进行中。
2. **上下文打包**：要改的代码 + 相关文件 + AGENTS.md + 对应 spec + 测试方案 + `security_strategy.md`；**明确告诉 AI 不要碰什么**。
3. **实现该 chunk**：一次一个功能点 / 函数族，禁止单体大块。
4. **安全检查验证 + 运维埋点验证**：对照 plan 的安全清单 + 运维清单；**运维能力（日志含 traceId / 监控指标 / 配置开关 / 健康检查 / 降级路径）写码当下就埋，别留到收尾**。
5. **自动化测试**：TDD 优先；用例名 / 注释带 `AC-xxx`；失败让 AI debug。
6. **commit 当存档点**：测试通过**立即** `git commit`，消息清晰**带 `FR-xxx`**；**commit 前必跑 `scripts/check_all` 最小质量门，全绿才提交**。
7. **更新开发进度**（**每轮对话结束必做**）：`开发进度/<功能名>/开发进度n.md`，记本轮做了什么（代码类：功能 / FR 号 / 涉及文件 / 代码位置 / 测试结果 / commit SHA）；单文件 ≤5000 tokens，超限新建 `开发进度<n+1>.md`。
8. **沉淀规范**：产出通用能力 → 更新 `AGENTS.md` 或新建 `XX约束.md`（含运维约定）；**"推翻常规做法" → 在 `docs/adr/` 记一篇 ADR**；否则跳过。
9. **定下一步**：写进开发进度总览，回步骤 1。

### C. 功能完成收尾（所有 chunk 完成后）

- **功能 README**（`开发进度/<功能名>/README.md`）：按受众 A 纯技术 / B 用户类（用户地图 + 技术说明）/ C 两者。
- **Feature Map**（`docs/feature-map/<功能名>.feature-map.md`）：功能-代码速查表 + **技术原理大白话注解**；涉建表加**表用途 / 字段用处 / 表关联注解**（建表用 Flyway）。
- **User-Ops**（`docs/user-ops/<功能名>用户操作手册.md`）：仅 B/C 类，细化到 功能→步骤→界面变化→预期结果。

### D. 文档规模硬规则

- 所有产出文档 **≤5000 tokens**（约 3000–4000 汉字），4000 预警；超限拆分（同主题顺序 / 总路由索引 / 其他）；机器校验 `python scripts/check_docs.py`。

## 产物

- `PROJECT/` 下实际代码 + 自动化测试
- `workflow_output/docs/测试方案/<功能名>测试方案.md`（仅需人工测试的功能）
- `workflow_output/开发进度/<功能名>/`（总览 + 进度 1..n.md + 必要时总路由.md + README.md）
- `workflow_output/docs/feature-map/<功能名>.feature-map.md`
- `workflow_output/docs/user-ops/<功能名>用户操作手册.md`（B/C 类）
- `workflow_output/项目规范约束/AGENTS.md`（持续织入）
- 干净的 git 历史（小 commit）

## 避坑（原文）

- **Never commit code you can't explain**——看不懂的代码逼 AI 加注释或改简单，否则别提交。
- 别让 AI 一口气实现五步（失控征兆：上下文爆炸 / 重复造轮子 / 命名打架 → **停下拆更小**）。
- 建表必须走 Flyway（`V<n>__描述.sql`，**已执行脚本不可改**），新脚本执行后回写 `specs/db_schema.md`。
- 接口完成即同步 API 契约（architecture §4.1 内部 / `docs/api` 对外）。
- **功能联动三处对齐**：plan 联动点 → 测试方案联动用例 → User-Ops 界面变化。
- 运维埋点别攒到收尾（跟着对应 chunk 当下埋、当下 commit）。
- **commit 前必跑 check_all**，别绕过质量门（`--no-verify` = 把错误埋进存档点）。

## 出口条件（满足才进 Phase 4）

- [ ] plan.md 所有步骤已勾选完成。
- [ ] 安全检查清单 + 运维考量清单已逐条落实。
- [ ] 需人工测试的功能测试方案已产出；**联动用例覆盖（含正 / 反 / 半选 / 批量）**。
- [ ] 功能 README 已产（按受众）；Feature Map 已产（含技术注解；涉建表含表注解 + Flyway + 回写 db_schema）。
- [ ] B/C 类功能 User-Ops 已产。
- [ ] 每轮进度已记录，文件 ≤5000 tokens，`check_docs.py` 无 FAIL。
- [ ] 全部自动化测试通过，`scripts/check_all` 全绿，已 commit。
- [ ] 规范已沉淀（若有通用能力产出）。

## 配套

- 权威原文：`2_编程类可迭代workflow/Phase3_开发实现.md`
- 速查版：`/phase3.1`（恢复开发先读）
- 提示词：`2_编程类可迭代workflow/项目模板/.github/prompts/2-implement.prompt.md`
- 收尾一条龙技能：`feature-wrapup`（`2_编程类可迭代workflow/项目模板/.claude/skills/feature-wrapup/SKILL.md`）
- 完成度判定：`2_编程类可迭代workflow/项目模板/workflow_output/开发进度/功能完成DoD清单.md`
- 各产物模板：`2_编程类可迭代workflow/项目模板/workflow_output/`
- 下一步：`/phase4`
