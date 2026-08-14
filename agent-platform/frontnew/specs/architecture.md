# 架构规格 — frontnew

## 1. 技术栈落点

| 层 | 选型 | 理由 |
|----|------|------|
| 框架 | Vue 3.4 + TypeScript 5.4 + Vite 5 | 与 frontend 完全一致，合回零成本 |
| UI 库 | Naive UI 2.38（暗色主题 + themeOverrides） | 保留组件生产力，皮肤层自定义 |
| 画布 | @vue-flow/core 1.41 + @vue-flow/background | 与现有 CanvasBoard 同库，节点组件可平移 |
| 状态 | Pinia | 仅存：当前主题、侧边栏折叠态 |
| 样式 | Sass + CSS 变量（tokens 层纯 CSS，无 Sass 依赖） | tokens 可被任意项目直接引用 |
| 图标 | @vicons/ionicons5 | 沿用 |
| mock | `src/mocks/*.ts` 类型化常量 + setTimeout 模拟异步 | 不装 axios、不装 mock 服务 |

**不引入**：vue-router 保留（需要多页面）但砍守卫；不装 crypto-js / dingtalk-jsapi / axios。

## 2. 分层架构

```
┌─────────────────────────────────────┐
│ views/          页面（5 个核心页）      │
├─────────────────────────────────────┤
│ components/     业务组件（节点卡片等）   │
├─────────────────────────────────────┤
│ theme/          主题系统（核心资产）     │
│  ├ tokens/      CSS 变量（4 主题×档位）│
│  ├ naive.ts     Naive UI overrides    │
│  └ useTheme.ts  切换逻辑+localStorage │
├─────────────────────────────────────┤
│ mocks/          类型化假数据            │
├─────────────────────────────────────┤
│ naive-ui        组件库（不换肤的部分）   │
└─────────────────────────────────────┘
```

**关键决策：皮肤层与结构层分离。** 组件只写结构+BEM 类名，颜色/阴影/圆角一律 `var(--token)`，禁止在组件里写死色值——这是 4 主题能一键切换的前提，也是合回 frontend 时改动最小的方式。

## 3. 主题系统实现

- tokens 按主题分文件：`tokens/neon-pulse.css`、`tokens/calm-slate.css`、`tokens/hybrid-glow.css`、`tokens/cineon.css`，各自定义 `:root[data-theme='xxx'] { ... }` 变量块；另有 `tokens/base.css` 放共享变量（圆角/时长/间距/字号）。
- 切换：`document.documentElement.dataset.theme = name` + localStorage 持久化 + Pinia 同步 Naive UI `themeOverrides`（computed 派生自当前主题）。
- Naive UI overrides 只覆盖全局色板与常用组件（Button/Card/Input/Tag/DataTable），深层组件差异交给 CSS 变量。

## 4. 画布模块

- `components/canvas/nodes/` 下 6 个节点组件 + `NodeCardBase.vue`（头部/内容/底部/连接桩/状态类名统一封装）；
- 状态驱动：`node.data.status` ∈ `idle|running|success|failed`（枚举与 frontend `CanvasNodeStatus` 完全一致），样式全部由状态类名 + CSS 变量派生，组件内无内联样式；
- 演示工作流 mock：`mocks/canvas.ts` 导出 ≥10 节点、≥9 连线，覆盖 6 类型 × 6 状态组合中的代表性样本；
- 性能：节点预览图占位用渐变 div（不引图片资源）；动画只动 transform/opacity；100 节点压力场景由 mock 生成器参数控制（`?nodes=100`）。

## 5. 构建与运行

- `pnpm dev` 直接可跑；无环境变量；无 `.env`；
- 构建：`vue-tsc && vite build`；Naive UI 按需引入（unplugin 可后期加，一期接受全量 dev 引入、构建用 naive 的 tree-shaking）。

## 6. 与 frontend 的合回路径（前瞻设计）

合回时按此顺序拆包，保证每步独立可验证：

1. `theme/tokens/*.css` → frontend `src/styles/themes/`（纯 CSS，直接拷）；
2. `NodeCardBase.vue` + 6 节点组件的 `<style>` 块 → frontend 对应组件换皮（结构 props 保持一致）；
3. MainLayout/Sidebar/AppHeader 样式 → 替换 frontend 同名文件样式块；
4. Naive UI overrides → frontend 主题文件。

**约束**：frontnew 组件的 props/emits 命名必须与 frontend 现有组件对齐（先读旧组件再设计新组件），避免合回时改接口。

## 7. 术语表

| 术语 | 大白话 | 案例 |
|------|--------|------|
| themeOverrides | Naive UI 的组件换肤配置 | 把按钮主色改成主题 accent |
| tree-shaking | 打包时自动删掉没用到的代码 | 只用 10 个组件就不打包其余 |
| BEM | CSS 类命名法：块__元素--修饰符 | `.node-card__header--running` |
| dataset.theme | html 标签上的 data-theme 属性 | `<html data-theme="cineon">` |
