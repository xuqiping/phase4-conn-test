---
slug: admin-users-roles
title: 用户管理与角色权限（管理员）
category: 管理员专区
sort: 10
permission: user:manage
---

# 用户管理与角色权限（管理员）

> 入口：侧栏「用户管理」「角色权限」。管人 + 管人能用什么模块。

## 一、用户管理

1. 用户列表可按用户名搜索，看每人的状态/角色/注册时间。
2. **改状态**（点用户行的状态操作）：
   - **封号（BANNED）/ 禁用（DISABLED）/ 锁定（LOCKED）**：填原因 → 该用户**立即被踢下线**，无法再登录。
   - **改回 ACTIVE** = 解封。
3. **保护规则**：不能封自己；最后一个持有「用户管理」权限的管理员不可封（防止全平台没人能管用户）。

## 二、角色权限（给用户开模块权限）

1. 「角色权限」页管理角色：每个角色挂一组权限码。
2. 把用户挂到角色 = 用户获得该角色全部权限。
3. 常用权限码对照：

| 权限码 | 用户能用 |
|---|---|
| `media:gen` | 视频生成、图片生成、视频反推 |
| `media:edit` | 视频剪辑 |
| `canvas:write` | 无限画布 |
| `asset:write` | 资产库 |
| `project-group:manage` | 项目组 |
| `knowledge:read` / `knowledge:write` / `knowledge:manage` | 知识库 读/写/管理 |
| `usage:view` / `pricing:manage` / `points:recharge` / `payment:config` | 账单 / 价表 / 充值 / 支付渠道 |
| `system:audit:read` | 审计日志 |
| `security:event:read` / `security:ban:manage` / `security:rule:manage` | 安全事件 / 封禁 / 安全规则 |
| `feedback:manage` / `help:manage` | 反馈处理 / 帮助文章 |
| `llm:config` | 进设置页配大模型（预置配置员） |

> 用户看不到某模块菜单 = 缺对应权限码，在这里挂上即可（用户重新登录或刷新后生效）。
