# 功能完成 DoD（Definition of Done）清单 · <项目名>

> 一个功能从 plan 到发布「做完」要打的全部勾，**单文件汇总**——替代散落在 6 个 Phase 文件里翻找出口条件。
> **主清单（本文件）= 所有 gate 的权威定义，定一次**；每个功能在自己 README 底部「DoD 勾选状态」复制适用项打勾。
> 级别裁剪：按项目级别（L0~L3，见工作流总览 `0_工作流总览.md` 的「文档分级裁剪表」）勾适用项，不适用标 `N/A`。
> **文档规模**：本文件不得超过 5000 tokens。

## 用法

- 本文件 = 权威 gate 定义，定一次，随工作流升级而更新。
- 每个功能 = 在其 `开发进度/<功能名>/README.md` 底部「DoD 勾选状态」复制**本功能适用**的项打勾。
- 一个功能只有 DoD 全绿（不适用的标 `N/A`）才算交付，可进下一功能 / 下一轮迭代。

## A. 规格（Phase 1）

- [ ] 本功能的 `FR-xxx` / `AC-xxx` 已在 PRD 定义且编号唯一
- [ ] 涉及的表已进全局 `specs/db_schema.md`（若有建表 / 改表）
- [ ] 关键架构决策已记 ADR（反常规决策才记）【L3】

## B. 计划（Phase 2）

- [ ] `plans/<功能名>.plan.md` 已出，Step 标注依赖的 FR 号
- [ ] 技术坑点预判表 + 安全检查清单 + 性能考虑已填
- [ ] 运维考量清单已填（日志 / 指标 / 健康检查 / 开关 / 降级）【L2/L3】

## C. 实现（Phase 3）

- [ ] 所有 plan chunk 已完成，commit 清晰（消息含 FR 号）
- [ ] `scripts/check_all` 全绿（compile + test + lint + typecheck + 文档校验）
- [ ] 功能 README 已产出（按受众 A / B / C）
- [ ] Feature Map 已产出（`docs/feature-map/`）
- [ ] User-Ops 已产出（B / C 类功能，`docs/user-ops/`）
- [ ] 需人工测试的已出测试方案（`docs/测试方案/`）
- [ ] 进度文件已更新到最新，总览状态准确

## D. 验证（Phase 4）

- [ ] 应用跑通，核心路径亲眼验证
- [ ] PRD 验收标准 `AC-xxx` 逐条核对通过（自动化 / 人工）
- [ ] User-Ops 逐项验证通过（若有）
- [ ] 性能评测完成且达标（或已记档后续迭代）【L2/L3】
- [ ] review 已做：8 维度结构化清单全结论 + 三个最怀疑位置 + 高危换模型交叉审【L2/L3】
- [ ] spec 符合性检查无未处置漂移（漂移清单已闭环或记入待办）
- [ ] `python scripts/check_docs.py` 无 FAIL

## E. 发布（Phase 5）【L2/L3】

- [ ] CI 全绿（check_all + Flyway validate + secret/依赖扫描 + 构建）
- [ ] 已部署，部署后验证清单通过
- [ ] 可观测性已接入，`docs/ops/监控告警说明.md` 该功能告警行已加
- [ ] 部署手册已更新（部署相关有变才更）

## F. 文档同步（全程；Phase 6 改 / 删亦复用）

- [ ] Feature Map / User-Ops / 功能 README / 进度总览 与代码一致
- [ ] `specs/db_schema.md` 已回写（涉及建表 / 改表）
- [ ] AGENTS.md / file_structure.md 无过期（`last_updated` 已更新）

## 级别速查（最少必勾）

| 级别 | 必勾 |
|---|---|
| L0 一次性脚本 | 允许 vibe，DoD 不强制 |
| L1 小工具 / MVP | A（AC）+ B（plan）+ C（核心）+ D（跑通 + AC 逐条） |
| L2 正式产品 | 全部 A~F |
| L3 对外 / 合规 | 全部 A~F（ADR / 对外 API / 安全 SLA 加严） |

## 相关文档

- 各 Phase 出口条件：见工作流的 Phase 2/3/4/5 文件（本清单是它们的单文件汇总，逐条来源对照各 Phase「出口条件」）。
- 文档分级裁剪表（L0~L3）：见工作流总览 `0_工作流总览.md`。
- 功能收尾一条龙（Claude Code）：`.claude/skills/feature-wrapup/SKILL.md`。

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| | | |
