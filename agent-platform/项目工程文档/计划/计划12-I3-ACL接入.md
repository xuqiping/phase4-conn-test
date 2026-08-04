---
description: "迭代 I3 · 召回与总结取数加 ACL 过滤（单套 ACL）"
created-date: 2026-07-27
---

# 计划12 · I3 · ACL 接入

> 子 plan。主索引：[计划12-个人记忆重设计-实现开发计划.md](计划12-个人记忆重设计-实现开发计划.md)。设计：[总体设计 §3.6 §3.7 §6](../设计/个人记忆重设计-总体设计.md)。

## 目标
召回（D）项目 scope 拼流水账时 `turn.user_id IN readableAuthors`；总结取数（E）候选 = 当前可召回人员集；summary 不变（仍只读自己）。

## 依赖
I1、I2、D、E。

## 文件清单（≤20）
- 改 `chat/service/internal/MemoryTurnPatcher.java`：项目 scope 加 `user_id IN readableAuthors(pid, reader)`
- 改 `chat/service/internal/MemoryConsolidationService.java`：项目总结取数候选 = readableAuthors ∩ 离职开关过滤后集
- 改 `chat/service/MemoryScopeResolver.java`：离职开关解析 + 「已离开人员·{用户名}·{departed_at}」标注
- 改 `chat/service/internal/MemoryScope.java`：DEPARTED 状态判定

## 动作（伪代码）
1. **召回 ACL 接入**：MemoryTurnPatcher 项目 scope 分支，拼流水账前 `turn.user_id IN (SELECT readableAuthors(pid, reader))`。
2. **总结取数 ACL 接入**：MemoryConsolidationService 项目总结候选 = readableAuthors(pid, 作者) ∩ 离职开关过滤后集。
3. **离职开关（L10）**：
   - 开 → 纳入 DEPARTED 人员，召回条目带「已离开人员·{用户名}·{departed_at}」标注。
   - 关 → 排除 DEPARTED 人员，**优先级高于人员多选**（即便勾了离职人员也不召回/取数）。
   - 同套控制召回 + 总结取数。
4. **summary 不受 ACL 影响**：仍只读自己（user_id=self），他人 summary 不可见。

## 本迭代相关安全向量
- [ ] 向量 14：ACL 接入（召回+取数强制过 readableAuthors）。
- [ ] 向量 2：项目成员交集（project_ids && listAccessibleProjectIds）。

## 本迭代相关联动点
- **L10 离职开关**：边界——优先级高于人员多选；同时控召回与取数；开则带标注。

## 本迭代相关运维
- 无新增（复用 D/E 可观测性）。

## 验证（出口条件）
- [ ] 召回 ACL 隔离 IT（A 未授权读不到 B 的流水账）。
- [ ] DEPARTED 曾赋权人员在开关开时可读。
- [ ] 离职开关关 → 即便勾离职人员也不召回/取数（优先级高于多选）。
- [ ] 离职开关开 → 召回条目带「已离开人员」标注。
- [ ] summary 不受 ACL 影响（他人 summary 不可见）。
