# M4 — scope 语义澄清(功能 README)

> 受众 B/C(用户直接操作 UI)。原待办 20+21 合并。纯前端,零后端/零迁移。

## 一句话
把对话底栏「写目标」与「读范围」混在一起的选器拆开、改名、加分组;记忆抽屉预览「指定项目」时加显式「包含总记忆」开关(默认关),不再静默注入总记忆。

## 用户地图
- **对话底栏**:见两组,左侧「记忆落库于」(写,带底纹)= 新事实存哪;右侧「读取记忆范围」(读)= AI 读哪些。中间分隔线。详见 [User-Ops](../../docs/user-ops/M4-scope语义澄清用户操作手册.md)。
- **记忆抽屉 → 预览范围「指定项目」**:多出「包含总记忆」开关。默认关 = 只预览选中项目;开 = 连总记忆。

## 技术说明
- 改动文件:`MemoryManagerPanel.vue`、`ChatView.vue`。
- 状态字段 `memProjectId`/`memIncludeGlobal`/`memReadProjectIds`(stores/chat.ts)沿用,零新增 state。
- custom 预览开关默认 OFF:语义对齐「指定项目 = 精确控制」,静默 ON 是 bug 来源。
- 详见 [Feature Map](../../docs/feature-map/M4-scope语义澄清.feature-map.md)。

## 验证
- vue-tsc 净;vitest 99/99;playwright-mcp 冒烟过(底栏两组渲染 + custom 开关显现)。截图:仓库根 `m4-bottom-bar.png`、`m4-preview-include-global-switch.png`。

## 关联
- plan:[plan.md](./plan.md) | 进度:[开发进度1.md](./开发进度1.md)
- 源待办:[速查表09 M4](../../../项目工程文档/项目功能介绍/速查表/09-个人记忆-演进与待办.md)
- commit:c6fe1d6a
