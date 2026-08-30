# architecture.md — 「高山流水」设计系统移植架构

> 版本 v1.0 · 2026-08-30。配套：[PRD.md](PRD.md)（需求与验收）、[testing_strategy.md](testing_strategy.md)、[file_structure.md](file_structure.md)。

## 1. 总体决策

**移植方向：frontend ← frontupdate。** frontend 为唯一活体（保留 19 个 commit 全部逻辑），frontupdate 仅作设计素材源，移植完成后不再双向同步。

三向基准：`git show 1aff8734:agent-platform/frontend/<path>`（frontupdate 内容沉淀时的 frontend 状态）。

```
frontupdate（设计系统 78 文件 + 45 重设计 + 3 双改）
        │
        ▼ 移植（分类处置，见 §4）
frontend（22 漂移 + 10 独有 + 45/3/78 并入）→ 唯一代码库
```

## 2. 设计系统分层（自 frontupdate 原样搬入，改一层动全站的依赖链）

```
tokens-ink.scss          原语层：16 传统色 + @font-face + 缓动 + reduced-motion
   ↓ 只被主题层引用
themes/ye-mo.scss        语义层：原语 → --bg-body/--text-1/... CSS 变量（暗）
themes/xuan-zhi.scss     语义层：同上（亮）
themes/{deep-space,dark-pro,cyber-glow}.scss   旧 3 套（保留、隐藏）
   ↓ CSS 变量
naive-overrides.ts       组件库接管：按 data-theme 出 GlobalThemeOverrides
   （夜墨→darkTheme 基座；宣纸→null 亮色基座；旧 3→LEGACY_PRIMARY 最小覆盖）
texture.scss             工具类：u-display-font / u-mist-layer（装饰 ≤0.12 红线）
   ↓
config/scenes.ts         场景注册表：12 模块 × {rgb, 诗签, 山形种子}，真值源
components/ModuleScene.vue   场景渲染（avif 懒加载 + ridgeGradients 山形渐变兜底）
components/PageHeader.vue    页头标准件（标题 + 场景 + 诗签）
components/InkEmptyState.vue 空态标准件（插画 + 短语）
```

**切换机制**：`stores/theme.ts` 维护 `ThemeName`（5 值联合类型）→ `document.documentElement.setAttribute('data-theme', name)` → CSS 变量层与 naive-overrides 层同源同切。App.vue 通过 computed 响应式喂给 `n-config-provider`。

**主题数据流**：

```
ThemeSwitcher（读 visibleThemes，写 setTheme）
   → useThemeStore（持久化 localStorage，迁移隐藏主题→ye-mo）
   → data-theme 属性 ─┬→ themes/*.scss（CSS 变量切换）
                      └→ naive-overrides（GlobalThemeOverrides 切换）
```

## 3. 与 frontnew 的关系（历史脉络，防混淆）

`agent-platform/frontnew/` 是 2026-08-14 的**独立原型项目**（mock 数据、无后端、4 主题比选、目标是节点卡片视觉验证），与本次移植**无代码血缘**。本次素材源是 `frontupdate/`（frontend 真副本重设计）。frontnew 不参与移植，仅其 specs 目录结构约定被沿用。

## 4. 文件五分类清单（范围真值源）

> 生成口径：`diff -rq --strip-trailing-cr` 对照三方（frontend HEAD / frontupdate / 基准 `1aff8734`）。CRLF 行尾差异是仓库噪声，一律剥离后再比。**执行前须重新生成核对一次**（防止规格与执行时点之间又有新 commit 落入 frontend）。

### A. 仅 frontupdate → 整体搬入 frontend

src 内 78 个（`src/assets/art/**` 69 个美术文件——brand/empty/login/scenes/workbench 五组三格式，frontend 原无 assets 目录；`src/components/{InkEmptyState,ModuleScene,PageHeader}.vue`；`src/config/scenes.ts`；`src/styles/{naive-overrides.ts,texture.scss,tokens-ink.scss}`；`src/styles/themes/{xuan-zhi,ye-mo}.scss`）+ src 外 6 个（`public/fonts/` 3 个：woff2/OFL 许可/css；`scripts/` 3 个：subset-font.py/check-contrast.mjs/font-glyphs.txt）。

搬入规则：原样复制，不改内容。index.html 追加字体 preload 一行（frontupdate 版即模板）。package.json **保留 frontend 版**（多 dagre 依赖，其余零差异）。

### B. 纯样式覆盖（45 个）→ 取 frontupdate 版本整文件替换

frontend 侧自基准后未改、frontupdate 侧已重设计，替换零逻辑损失：

`App.vue`、`main.ts`、`components/{AppHeader,Sidebar}.vue`、`components/chat/{ChatInput,SessionList}.vue`、`components/knowledge/{DocumentManager,RagAskPanel}.vue`、`components/settings/{SecuritySettingsTab,UserProviderTab}.vue`、`layouts/{AuthLayout,MainLayout}.vue`、`stores/theme.ts(+test)`、`views/`：AgentDetailView(+test)、AgentHallView、AssetListView、AssetProjectView、BillingAdminView、ChatView、ExecutionMonitorView、FeedbackCenterView(+test)、ImageGenView、KnowledgeView、LoginView、MyWalletView、ProjectGroupsView、SettingsView、VideoEditView、admin/{AdminFeedbackView(+test)、AdminHelpArticlesView(+test)、PaymentChannelConfigView(+test)、PricingConfigView.test.ts、RoleManageView、UserManageView、WalletAdminView}、admin/security/{BanManageView,RiskDashboardView,RuleConfigView,SecurityEventView}。

> 注意：`stores/theme.test.ts`、`PricingConfigView.test.ts` 在此列（frontupdate 已改测试），但 `stores/theme.ts` 落地时要叠加 FR-3 的 hidden 标志与迁移逻辑（B 类唯一允许的增量改动，测试同步补）。

### C. 纯漂移（22 个）→ 保留 frontend 版本，不做合并

frontupdate 侧与基准完全相同（**frontupdate 从未重设计它们**——它们在 frontupdate 里本来就靠令牌系统自动换肤），frontend 侧含 19 个 commit 的逻辑修复：

`api/{billing,llm(+test),media,request,system}.ts`、`components/canvas/{CanvasBoard(+test),PropertyPanel(+test),ReferencePreview,nodeClone(+test)}.*`、`components/settings/ProviderManageTab.vue`、`stores/{chat(+test),projectGroup(+test)}.ts`、`types/canvas.ts`、`utils/canvasVideoAttachments(+test).ts`、`views/VideoGenView.test.ts`。

处置：不动文件，仅在人工走查时校验视觉一致性（FR-4 第二行）；发现硬编码旧色值按 FR-4 替换语义变量。

### D. 三向合并（3 个）→ frontend 逻辑 + frontupdate 呈现

`views/CanvasView.vue`、`views/VideoGenView.vue`、`views/admin/PricingConfigView.vue`。

合并方法（P2 计划展开，此处定原则）：
1. 以 frontupdate 版为底板（保呈现层：模板结构/class/scss 段）；
2. 把 frontend 版相对基准的 diff 逐块回放（保逻辑：script 变更、新增模板节点、新绑定）；
3. 冲突块裁决基准 = PRD §5 FR-5 表格「必保的 frontend 逻辑」列；
4. 合并后跑该文件全部测试 + testing_strategy §3 定向回归。

### E. 仅 frontend（10 个）→ 保留 + 按新风格重设计（FR-4）

`views/admin/logs/AuditLogView.vue`（全套对齐：PageHeader + admin 场景 + InkEmptyState + 令牌）；
`components/canvas/canvasClipboard.ts(+test)`、`utils/{autoLayout,groupEdges,mediaLimits}.ts(+test)`（纯逻辑模块，无自有 UI；其界面落点在 C 类 CanvasBoard/CanvasView 与 D 类合并文件中，走查覆盖）。

frontupdate 的 router 缺 AuditLogView 文件问题是其自身快照不完整所致；frontend 侧路由+文件本就齐全，移植后自然修复，无需额外动作。

## 5. 主题隐藏机制（FR-3 落点）

```
stores/theme.ts:
  interface ThemeMeta { ...; hidden?: boolean }
  THEME_LIST: 5 项，旧 3 项 hidden: true
  visibleThemes = THEME_LIST.filter(t => !t.hidden)   // ThemeSwitcher 改读此项
  initTheme(): 存量值 ∈ 隐藏集合 → setTheme('ye-mo')（持久化改写）
  默认值: 'ye-mo'
```

保留物：旧 3 套 scss、`ThemeName` 联合类型 5 值、naive-overrides `LEGACY_PRIMARY` 分支、THEME_LIST 元信息。恢复对外可见 = 移除 hidden 标志，一行改动。

> `components/ThemeSwitcher.vue` 不在五分类清单内（三方完全一致），但 FR-3 需把它 `v-for` 的数据源从 `THEME_LIST` 改为 `visibleThemes`——属于清单外的**登记增量**，与 package.json（+1 script）、index.html（+1 preload）同为仅有的三个清单外文件改动，除此之外不得动清单外文件。

## 6. 静态资产与构建

- 场景图仅 `import.meta.glob('.../*.avif')` 被引用 → webp/png 不进 dist，仅存于仓库（素材留档，PRD §3 不清理）；
- 字体走 `public/fonts/` 静态服务 + index.html preload，不经构建管线；再子集化用 `scripts/subset-font.py`（输入 `font-glyphs.txt`，改诗签/文案新增字形后需重跑——流程写入 file_structure.md）；
- `check-contrast.mjs` 挂 `npm run check:contrast`（package.json scripts 增一行，这是 B 类之外唯一允许的 package.json 变更）。

## 7. 风险与对策

| 风险 | 对策 |
|------|------|
| 执行时 frontend 又有新 commit，清单过期 | §4 头部「执行前重新生成核对」为强制步骤；新增双向改动文件自动落入 D 类流程 |
| 三向合并漏回放逻辑块 | D 类逐文件列 frontend-diff 清单作核对表；合并测试基准 = 该文件相关测试全绿 + §3 定向回归 |
| C 类文件含旧主题硬编码色值残留 | 人工走查双主题全覆盖（testing_strategy §4），发现即改语义变量 |
| 45 个 B 类文件覆盖时丢 frontend 微修复 | B 类判定标准 = frontend 侧与基准**完全一致**（diff 为空），有任意差异即自动降入 D 类 |
| 宣纸（亮）主题下暗色遗留组件不可读 | 走查重点项；naive-overrides 已接管主要组件，风险集中在自定义 scss 段 |
| 50MB 资产拖慢 git 操作 | 已接受（用户决策：全保留）；后续如需瘦身另立变更单 |
