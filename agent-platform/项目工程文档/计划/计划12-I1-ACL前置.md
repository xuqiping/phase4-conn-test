---
description: "迭代 I1 · ACL 前置（V48 + memory_recall_acl + readableAuthors resolver）"
created-date: 2026-07-27
---

# 计划12 · I1 · ACL 前置

> 子 plan。主索引：[计划12-个人记忆重设计-实现开发计划.md](计划12-个人记忆重设计-实现开发计划.md)。设计：[总体设计 §3.6 §6](../设计/个人记忆重设计-总体设计.md)。

## 目标
建 ACL 表 + `readableAuthors` resolver；**前置**——D/E 依赖，须在 D/E 前。

## 依赖
A。

## 文件清单（≤20）
- `db/migration/V49__create_memory_recall_acl.sql`：`UNIQUE(project_id, reader_user_id, target_user_id)`
- `chat/entity/MemoryRecallAcl.java`
- `chat/mapper/MemoryRecallAclMapper.java`
- `chat/service/internal/MemoryRecallAclResolver.java`：`readableAuthors(projectId, reader)`

## 动作（伪代码）
> **版本偏移**：原写 V48，V48 已被迭代 A 补丁（memory_conflicts 放宽）占用 → 本迭代走 **V49**（H DROP 顺延 V50）。版本号软标签。

1. V49 建表：reader→target 授权行；owner 兜底全读无需 ACL 行。
2. `readableAuthors(projectId, reader)`：
   - reader 是 owner → 返回项目全部成员 user_id（含 DEPARTED 曾赋权）。
   - reader 是 admin/member → `SELECT target_user_id FROM memory_recall_acl WHERE project_id AND reader_user_id=reader` ∪ {reader 自己}。
   - recall_admin=true 的 admin 可配置 ACL（与普通 admin 区分）。
3. 返回集含 DEPARTED 曾赋权（保交接，是否纳入召回由 L10 离职开关控，不在本迭代）。

## 本迭代相关安全向量
- [ ] 向量 14：项目记忆读取 ACL（owner 兜底；summary 不受 ACL 影响——本迭代只建 resolver，接入在 I3）。

## 本迭代相关联动点
- **L10 离职开关前置依赖**：readableAuthors 返回集含 DEPARTED，由 L10 开关在 I3 接入时过滤+标注。

## 本迭代相关运维
- **配置开关**：recall_admin 标记可后台改。做。

## 验证（出口条件）
- [ ] resolver 五路径单测全绿（owner / admin / member / recall_admin / DEPARTED 曾赋权）。
- [ ] owner 无 ACL 行也能全读。
- [ ] V49 可逆（DROP TABLE 注释附逆操作）。
- [x] 进行中：🚧 2026-07-27 落地单测（见 [开发进度3.md](../../workflow_output/开发进度/计划12-个人记忆重设计/开发进度3.md)）。
