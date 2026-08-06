# M4 scope 语义澄清 · Feature Map(功能-代码速查)

> 涉文件 2 个,零建表/零后端/零迁移。

## 功能大白话
平台记忆分「写目标」(新事实落哪个项目) 和「读范围」(召回时读哪些记忆) 两件事。改前:
1. 对话底栏把两件事混在一行,用户分不清哪个选择器干嘛。
2. 记忆管理抽屉里「预览范围」选「指定项目」时,系统**偷偷把总记忆也注入了**,用户以为只看指定项目其实不是。

M4 把两者拆开、改名、加开关,所见即所得。

## 代码速查
| 功能点 | 文件 | 关键位置 |
|---|---|---|
| custom 预览开关 | `agent-platform/frontend/src/components/chat/MemoryManagerPanel.vue` | `previewCustomIncludeGlobal` ref(~L322);`effectivePreviewScope()` custom 分支(~L331);模板 `<n-switch v-if="previewScopeMode==='custom'">`(~L43) |
| 底栏写/读分组 | `agent-platform/frontend/src/views/ChatView.vue` | 模板 `.chat-view__mem-scope--write/--read` 两 div + `.chat-view__mem-divider`(~L148-178);CSS `.chat-view__mem-divider` + 分组底色(~L438) |

## 状态字段(既有,未改)
`frontend/src/stores/chat.ts`:
- `memProjectId` — 写目标(null=总记忆会话)
- `memIncludeGlobal` — 读:总记忆 on/off
- `memReadProjectIds` — 读:开启读取的项目集

## 数据流
- 底栏 → `chatStore.scopeRequestPayload` → 每次发消息带 scope → 后端持久化 + 召回按 scope 注入。
- 抽屉预览 → `effectivePreviewScope()` → `POST /api/chat/memories/preview` payload `{includeGlobal, projectIds}`。

## 逆向/排错
- 「包含总记忆」开关不显?→ 仅 `previewScopeMode==='custom'` 显,检查模式 select。
- 底栏两组没分隔?→ 查 `.chat-view__mem-divider` CSS 是否被主题覆盖。

## 关联
- 设计:[速查表09 待办 M4](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆-演进与待办.md)
- 进度:[开发进度/M4-scope语义澄清/开发进度1.md](../../开发进度/M4-scope语义澄清/开发进度1.md)
