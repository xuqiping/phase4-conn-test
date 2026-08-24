# 帮助中心 · 全模块使用说明（34 篇）

> 交付物：用户端「反馈与帮助 → 使用说明」的初始文章库。
> 来源：`../user-ops/` 各用户操作手册的终端用户口径改写（傻瓜式）。
> 落地方式：Flyway `V150__help_articles_seed.sql` 由本目录文件生成（`ON CONFLICT (slug) DO NOTHING` 幂等，全部已发布）。
> 权限门控：`V149` 给 help_articles 加 `required_permission` 列——**用户只能看到自己持有权限模块的文章**（admin 全见）。

## 文章清单（slug · 分类 · 所需权限）

| 文件 | slug | 分类 | required_permission |
|---|---|---|---|
| 01 | login-register | 快速上手 | （全员） |
| 02 | account-security | 快速上手 | （全员） |
| 03 | feedback-help | 快速上手 | （全员） |
| 04 | chat-basics | 智能对话 | （全员） |
| 05 | memory-overview | 智能对话 | （全员） |
| 06 | memory-scope | 智能对话 | （全员） |
| 07 | memory-conflict | 智能对话 | （全员） |
| 08 | knowledge-upload | 知识库 | （全员） |
| 09 | knowledge-qa | 知识库 | （全员） |
| 10 | knowledge-privacy | 知识库 | （全员） |
| 11 | wallet-points | 钱包与积分 | （全员） |
| 12 | project-groups | 钱包与积分 | project-group:manage |
| 13 | video-gen | 视频创作 | media:gen |
| 14 | video-reverse | 视频创作 | media:gen |
| 15 | video-edit | 视频创作 | media:edit |
| 16 | image-gen | 图片创作 | media:gen |
| 17 | media-history | 图片创作 | media:gen |
| 18 | canvas-basics | 无限画布 | canvas:write |
| 19 | canvas-advanced | 无限画布 | canvas:write |
| 20 | director-3d | 无限画布 | canvas:write |
| 21 | assets-basics | 资产库 | asset:write |
| 22 | assets-share | 资产库 | asset:write |
| 30 | admin-users-roles | 管理员专区 | user:manage |
| 31 | admin-billing | 管理员专区 | usage:view |
| 32 | admin-pricing | 管理员专区 | pricing:manage |
| 33 | admin-wallet | 管理员专区 | points:recharge |
| 34 | admin-payment | 管理员专区 | payment:config |
| 35 | admin-audit | 管理员专区 | system:audit:read |
| 36 | admin-security | 管理员专区 | security:event:read |
| 37 | admin-feedback | 管理员专区 | feedback:manage |
| 38 | admin-help-articles | 管理员专区 | help:manage |
| 39 | admin-settings | 管理员专区 | llm:config |
| 40 | admin-knowledge-ops | 管理员专区 | knowledge:manage |
| 41 | admin-ops | 管理员专区 | ROLE_admin |

## 文件格式

```markdown
---
slug: 英文短链（小写/数字/连字符，创建后不可改）
title: 文章标题
category: 分类（=用户端左侧模块目录）
sort: 分类内排序（小在前）
permission: 权限码（空=全员；ROLE_admin=仅管理员）
---

# markdown 正文
```

## 改了文章怎么同步

- 线上改：直接「帮助文章」管理页编辑（slug 不可改），无需发版。
- 重新生成种子：本目录文件 → 重跑生成脚本产出新 Flyway 版本（勿改 V150，已入库的按 slug ON CONFLICT 跳过）。
