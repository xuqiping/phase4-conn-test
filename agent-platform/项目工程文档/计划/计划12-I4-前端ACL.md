---
description: "迭代 I4 · 前端 ACL（花名册页 + ACL 配置矩阵 + 总结取数选人）"
created-date: 2026-07-27
---

# 计划12 · I4 · 前端 ACL

> 子 plan。主索引：[计划12-个人记忆重设计-实现开发计划.md](计划12-个人记忆重设计-实现开发计划.md)。设计：[总体设计 §3.4 §3.6](../设计/个人记忆重设计-总体设计.md)。

## 目标
E2E 花名册 + ACL 配置生效 + 总结取数选人。

## 依赖
I1-I3、F。

## 文件清单（≤20）
- `frontend/src/components/memory/MemoryRosterPanel.vue`：项目成员花名册（含已退出）
- `frontend/src/components/memory/MemoryRecallAclMatrix.vue`：owner/admin ACL 配置矩阵（reader→target 勾选）
- `frontend/src/components/memory/MemoryConsolidationPeoplePicker.vue`：总结取数选人（仅自己/某几人/全部）
- 改 `frontend/src/api/memory.ts`：roster + recall-acl 客户端

## 动作（伪代码）
1. **MemoryRosterPanel**：列项目成员（含已退出 status=DEPARTED + departed_at）+ role + recall_admin 标记；仅 owner/recall_admin 可进配置。
2. **MemoryRecallAclMatrix**：reader 行 × target 列勾选矩阵；owner 兜底全读无需配（提示）；保存调 PUT。
3. **MemoryConsolidationPeoplePicker**：项目总结取数选人组件（仅自己 / 成员多选 / 全部人员）；与方向选择（I/O/全部）联动；候选 = readableAuthors ∩ 离职开关过滤后集（前端据开关禁用/启用离职人员选项）。

## 本迭代相关安全向量
- [ ] 向量 14：前端 ACL 配置边界（非 owner/recall_admin 不可见配置 UI）。

## 本迭代相关联动点
- **L10 离职开关**：选人组件据开关禁用/启用离职人员选项（开关优先级高于勾选）。
- **L11 ACL 授权**：矩阵保存即影响召回+取数候选。

## 本迭代相关运维
- 无新增。

## 验证（出口条件）
- [ ] E2E 花名册含已退出人员。
- [ ] owner/recall_admin 可配 ACL，member 不可见配置 UI。
- [ ] ACL 配置后召回生效（A 授权读 B）。
- [ ] 总结取数选人（仅自己/某几人/全部）+ 方向联动。
- [ ] 离职开关关时选人组件禁用离职人员。
