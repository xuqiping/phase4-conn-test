---
description: "迭代 I2 · 花名册端点 + ACL 配置端点"
created-date: 2026-07-27
---

# 计划12 · I2 · ACL 端点

> 子 plan。主索引：[计划12-个人记忆重设计-实现开发计划.md](计划12-个人记忆重设计-实现开发计划.md)。设计：[总体设计 §3.6 §6](../设计/个人记忆重设计-总体设计.md)。

## 目标
roster + recall-acl 端点；owner / recall_admin 配置权边界。

## 依赖
I1、D、E。

## 文件清单（≤20）
- 改 `chat/controller/MemoryController.java`：roster + recall-acl 端点
- `chat/dto/MemoryRosterVO.java` / `MemoryRecallAclRequest.java` / `MemoryRecallAclVO.java`
- 改 `chat/service/internal/MemoryRecallAclResolver.java`：配置写入
- 改 `chat/mapper/MemoryProjectMemberMapper.java`：roster 查询（含已退出）
- 改 `chat/mapper/MemoryRecallAclMapper.java`：upsert / delete

## 动作（伪代码）
1. `GET /projects/{pid}/roster`：返项目成员（含已退出 status=DEPARTED + departed_at）+ role + recall_admin。
2. `PUT /projects/{pid}/recall-acl`：仅 owner 或 `recall_admin=true` admin 可调；写 reader→target 授权集（全量替换）。
3. `GET /projects/{pid}/recall-acl`：返当前授权矩阵。
4. 配置权边界：非 owner / 非 recall_admin admin / member 调 PUT → 403。

## 本迭代相关安全向量
- [ ] 向量 14：ACL 配置权边界（owner / recall_admin）。
- [ ] 向量 15：ACL 配置操作留审计。

## 本迭代相关联动点
- **L11 流水账挂载/授权**：ACL 授权集决定 reader 可读哪些 target 的流水账（召回+总结取数共用，接入在 I3）。

## 本迭代相关运维
- **审计**：ACL 变更全留审计日志。做。

## 验证（出口条件）
- [ ] owner/admin/member/recall_admin 配置权边界 IT 全绿。
- [ ] roster 含已退出人员。
- [ ] 非 owner/recall_admin 调 PUT → 403。
