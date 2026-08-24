---
slug: admin-help-articles
title: 帮助文章管理（管理员）
category: 管理员专区
sort: 90
permission: help:manage
---

# 帮助文章管理（管理员）

> 入口：侧栏「帮助文章」。用户端「反馈与帮助 → 使用说明」读的就是这里发布的文章。

## 一、新建文章

1. 点「新建文章」→ 填：
   - **slug**：英文短链名，只能小写字母/数字/连字符（如 `how-to-recharge`）。**创建后不可改**。
   - **标题、分类、排序**：分类就是用户端左侧的模块目录（如「视频创作」）；排序小的排前面。
   - **所需权限**：留空 = 所有登录用户可见；填权限码（如 `media:gen`）= 只有持该权限的用户（和管理员）能看到这篇。**用户只能看到自己有权模块的说明**，靠的就是这个字段。
   - **正文**：markdown，可点「预览」。
2. 保存 → 提示「已创建（未发布，用户不可见）」。

## 二、发布 / 下架

- 列表「发布」列开关打开 → 用户端即刻可见；关掉 = 下架（用户直输旧链接显示加载失败）。

## 三、编辑与删除

- 编辑：slug 输入框禁用（不可改），其余可改。
- 删除：**二次确认**（硬删不可恢复，slug 释放后可重建同名文章）。

## 常用权限码速查

| 填这个 | 只有这些用户能看到 |
|---|---|
| （留空） | 全体登录用户 |
| `media:gen` | 有视频/图片生成权限的 |
| `media:edit` | 有视频剪辑权限的 |
| `canvas:write` | 有画布权限的 |
| `asset:write` | 有资产库权限的 |
| `project-group:manage` | 有项目组权限的 |
| `knowledge:manage` | 知识库管理员 |
| `usage:view` / `pricing:manage` / `points:recharge` / `payment:config` | 对应计费管理岗 |
| `system:audit:read` | 审计岗 |
| `security:event:read` | 安全管理岗 |
| `feedback:manage` / `help:manage` | 反馈审核岗 / 内容岗 |
| `user:manage` / `role:manage` | 用户/角色管理岗 |
| `llm:config` | 模型配置岗 |
| `ROLE_admin` | 仅系统管理员 |
