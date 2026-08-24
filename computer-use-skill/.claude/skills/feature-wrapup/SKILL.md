---
name: feature-wrapup
description: 功能开发完成时的收尾一条龙。产出/更新功能 README + Feature Map + User-Ops（B/C 类）+ 开发进度，跑 check_all，确认文档同步，最后 commit。对应 Phase 3 的 C 收尾 + Phase 4 准备。
---

# 功能收尾（feature-wrapup）

把「功能做完」到「功能交付」之间散落的收尾动作用一条龙串起来，避免漏文档、漏测试、漏 commit。

## 触发时机

- 一个功能的所有 plan.md chunk 已勾选完成。
- 用户说「这个功能做完了 / 收尾 / 验收前」。

## 执行清单（逐项过，缺的当场补）

### 1. 文档产物（Phase 3 C 收尾）

- [ ] **功能 README**：`workflow_output/开发进度/<功能名>/README.md`——按受众（A 开发 / B 决策 / C 用户）写用户地图 + 技术说明。
- [ ] **Feature Map**：`workflow_output/docs/feature-map/<功能名>.feature-map.md`——粗略代码速查表（前端/后端/数据库/测试位置 + 调用链 + 关键技术落点 + 建表 Flyway 注解）。
- [ ] **User-Ops**（仅 B/C 类用户可见功能）：`workflow_output/docs/user-ops/<功能名>用户操作手册.md`——细化到每步操作的傻瓜式文档。
- [ ] **建表回写**：若本功能新增/改了表，回写全局 `workflow_output/docs/specs/db_schema.md`（ER 图 + 数据字典 + Flyway 版本清单）。

### 2. 进度同步（每一轮对话结束必做）

- [ ] `workflow_output/开发进度/<功能名>/` 下逐轮进度文件已更新到最新。
- [ ] `workflow_output/开发进度/开发进度总览.md` 该功能状态改为「完成」。
- [ ] 若 review 发现漂移，已记入总览「规格漂移待办」并处置。

### 3. 质量门（铁律 #9）

- [ ] 跑 `scripts/check_all`（后端 compile+test / 前端 tsc+lint+test / 文档校验）全绿。
- [ ] `python scripts/check_docs.py` 无 FAIL。

### 4. commit（铁律 #7 当存档点）

- [ ] 一个清晰 commit，消息含功能名 + 关键变更（如 `feat: <功能名> 完成 <要点>`）。
- [ ] commit 前确认 pre-commit hook（check_all + gitleaks）通过。

### 5. 归档（功能交付后，非每次收尾必做）

> 触发条件：功能已交付（Phase 4/5 走完 + 部署）**或** 逐轮进度文件已 > 10 个、新会话加载变慢。防进度文件失控膨胀（上下文工程 Compress）。

- [ ] 通读该功能所有 `开发进度n.md`，产 `archive/<功能名>/<功能名>开发历程总结.md`（≤5000 tokens，只留关键决策/踩坑/最终结果/关联 ADR-FRD 编号）。
- [ ] 原始逐轮文件 + 功能总览移入 `archive/<功能名>/`（移不删）。
- [ ] `开发进度总览.md`：「功能进度汇总」删该行 → 「已归档功能」加一行摘要。
- [ ] Feature Map / User-Ops / README / 测试方案**留原位不归档**。

## 完成标志

所有清单打勾 + 已 commit + 进度总览状态更新。完成后可进 Phase 4 运行验证。

## 关联

- 收尾标准细节：`Phase3_开发实现.md` 的 C 收尾 + `Phase3.1_开发实现速查.md`。
- 文档产物全景：`0_文档产物与引用关系总览.md`。
