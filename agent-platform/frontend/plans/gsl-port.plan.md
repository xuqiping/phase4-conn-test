# 「高山流水」设计系统移植 实现计划（plan.md）

> 版本 v1.0 · 2026-08-30 · 依据 [specs/PRD.md](../specs/PRD.md) 等四件套生成。
> 铁律：本计划只含伪代码，不含真代码；每个 Chunk 独立可验证、独立 commit；P3 执行中发现不合理须回改本文件并注明原因。
> 基准 commit `1aff8734`；执行前 C0 重新核对分类清单（frontend 又前进则以最新 HEAD 为准）。

## Chunk 总览与依赖序

```
C0 基线核对 ──→ C1 A类搬入 ──→ C2 基础设施换肤 ──→ C3 业务页换肤 ──→ C4 管理页换肤
                                                     │
                     ┌───────────────────────────────┤
                     ▼               ▼               ▼
                 C5 画布合并     C6 视频生成合并   C7 定价合并
                                                     │
                     C8 审计日志重设计 ←─────────────┘
                                                     │
                     C9 主题隐藏+迁移 ──→ C10 终验
```

---

## C0 · 基线核对（半天内，零代码）

- **目标**：冻结范围真值，记录绿色基线。
- **动作**：
  1. 重跑 architecture §4 分类命令（`diff -rq --strip-trailing-cr` + 三方对照，基准仍 `1aff8734`，HEAD 取执行时点）；
  2. 若某文件从 B 类（frontend 未动）变为 D 类（双向改动）→ 自动并入 C5~C7 对应合并流程；若 frontend 新增文件 → 并入 C8 重设计清单；
  3. 记录 `npx vitest run` 通过数（基线 969）与 `npx vue-tsc` 零错误；
  4. `git checkout -b feat/gsl-port`（在 beifen 分支派生）。
- **涉及文件**：0（只读核对）。
- **依赖**：无。
- **验证**：分类清单落盘到本文件附录 A（执行时点快照）；基线数字写入 commit message。

## C1 · A 类搬入（设计系统底座，纯增量）

- **目标**：frontupdate 独有的 84 个文件原样进入 frontend，应用行为零变化（尚无消费者）。
- **动作**（伪代码）：
  ```
  copy frontupdate/src/assets/art/**      → frontend/src/assets/art/**      # 69 个，目录全新
  copy frontupdate/public/fonts/**        → frontend/public/fonts/**        # 3 个（woff2/OFL/css）
  copy frontupdate/scripts/{subset-font.py,check-contrast.mjs,font-glyphs.txt} → frontend/scripts/
  copy frontupdate/src/styles/{tokens-ink.scss,texture.scss,naive-overrides.ts} → frontend/src/styles/
  copy frontupdate/src/styles/themes/{ye-mo,xuan-zhi}.scss → frontend/src/styles/themes/
  copy frontupdate/src/components/{ModuleScene,PageHeader,InkEmptyState}.vue → frontend/src/components/
  copy frontupdate/src/config/scenes.ts   → frontend/src/config/scenes.ts
  edit index.html   : 追加字体 <link rel="preload">（frontupdate 版即模板）
  edit package.json : scripts 追加 "check:contrast": "node scripts/check-contrast.mjs"（依赖零新增）
  ```
- **涉及文件**：84 个纯复制 + 2 个一行编辑（编辑复杂度 ≤20；复制件逐字节原样，禁止顺手改）。
- **依赖**：C0。
- **验证**：`vitest run` 全绿（数量=基线）；`vue-tsc` 零错误；`npm run build` 成功，且**未被引用的** webp/png 不进 dist（被引用者如 seal-logo.webp 属正常）；`npm run check:contrast` 首跑绿；dev server 启动无新增告警。
- **无障碍**：tokens-ink.scss 的 prefers-reduced-motion 全局降级随文件落地（不得删）。

## C2 · B1 基础设施换肤（14 文件）

> **执行偏离记录（P3 C1 实测）**：`stores/theme.ts(+test)` 从 C2 提前到 C1——A 类新文件（naive-overrides/ModuleScene）引用 5 值 `ThemeName`，旧 3 值类型下 vue-tsc 三个 TS2367/2322/2353 错，整 chunk 绿闸门要求类型先扩。C2 实际 12 文件。

- **目标**：应用骨架（入口/布局/顶栏/侧栏/主题 store/全局会话壳）切换到设计系统，5 主题全部可用（暂不隐藏）。
- **动作**：
  ```
  foreach f in [App.vue, main.ts, components/AppHeader.vue, components/Sidebar.vue,
                components/chat/{ChatInput,SessionList}.vue,
                components/knowledge/{DocumentManager,RagAskPanel}.vue,
                components/settings/{SecuritySettingsTab,UserProviderTab}.vue,
                layouts/{AuthLayout,MainLayout}.vue, stores/theme.ts, stores/theme.test.ts]:
      copy frontupdate/f → frontend/f    # 整文件替换
  ```
  main.ts 导入顺序随之生效：原语 → 变量 → 主题(5套) → 纹理 → 全局。默认主题变 ye-mo（旧 3 套仍可选，中间态可接受，C9 收口）。
- **涉及文件**：14。
- **依赖**：C1（新样式文件与字体已就位）。
- **验证**：vitest 全绿（theme.test.ts 换 frontupdate 版后随动）；vue-tsc 净；手测：登录页泼墨、主布局双主题（夜墨/宣纸）切换无刷新、旧主题 deep-space 仍可切（scss 全量导入）；首屏无 FOUC（App.vue onMounted initTheme）。
- **联动验收**（对应联动点 L1/L2）：切换主题后侧栏/顶栏/Naive 组件（按钮/弹窗/下拉）三处同步变色；刷新保持；logout 后主题保持（clearAuthStorage 只清 3 个 auth 键，已核实）。

## C3 · B2 业务页换肤（17 文件）

- **目标**：主功能页面切新视觉。
- **动作**：`copy frontupdate/f → frontend/f`，对象：views/ 下 AgentDetailView(+test)、AgentHallView、AssetListView、AssetProjectView、BillingAdminView、ChatView、ExecutionMonitorView、FeedbackCenterView(+test)、ImageGenView、KnowledgeView、LoginView、MyWalletView、ProjectGroupsView、SettingsView、VideoEditView。
- **涉及文件**：17。
- **依赖**：C2（MainLayout/PageHeader/场景组件已生效）。
- **验证**：vitest 全绿；vue-tsc 净；手测抽样 5 页（Chat/Knowledge/ImageGen/Login/Settings）双主题可读、场景画+诗签出现、空态用 InkEmptyState；Settings 内 ProviderManageTab（C 类保留件）视觉协调无旧色残留。

## C4 · B3 管理页换肤（13 文件）

- **目标**：管理后台页面切新视觉（审计日志页除外，C8 处理）。
- **动作**：`copy frontupdate/f → frontend/f`，对象：admin/ 下 AdminFeedbackView(+test)、AdminHelpArticlesView(+test)、PaymentChannelConfigView(+test)、RoleManageView、UserManageView、WalletAdminView、security/{BanManageView,RiskDashboardView,RuleConfigView,SecurityEventView}。
  **注意**：`PricingConfigView.test.ts` 属 45 清单但**不在本 Chunk**——其组件是 C7 合并件，测试随 C7 落地，避免测试先于实现变红。
- **涉及文件**：13。
- **依赖**：C2。
- **验证**：vitest 全绿；vue-tsc 净；手测：用户/角色/反馈/帮助/支付/钱包/security 四页双主题；RiskDashboard 图表配色走石青/石绿/暮山紫序列。

## C5 · D 类合并 ① CanvasView.vue（1 文件）

- **目标**：frontupdate 呈现 + frontend 四个 commit 的画布新逻辑，零功能回归。
- **动作**（合并法，伪代码）：
  ```
  base   = git show 1aff8734:frontend/src/views/CanvasView.vue
  底板   = frontupdate 版（6 个样式 hunk：页头 2a3/10-16/19/24 + 模板插入 401a + 样式段 2749d）
  回放   = git diff base..HEAD -- CanvasView.vue 的 7 个逻辑 hunk：
           @@143（粘贴交互）@@409（导入）@@703（mention 焦点/组边）@@1313（submitVideoOnly）
           @@1577（rerunAll）@@1673（上传文件+组边拉线）@@2546（媒体预览）
  结果   = 底板 + 逐 hunk 回放；冲突块裁决=逻辑块以 frontend 为准、class/scss 块以 frontupdate 为准
  ```
- **涉及文件**：1。
- **依赖**：C1（PageHeader/场景）、C3（同代视图风格参照）。
- **验证**：`vitest run` 全绿（重点 CanvasBoard/canvasClipboard/groupEdges/autoLayout 相关套件）；vue-tsc 净；手测合并回归三件（testing_strategy §3）：组拉线直连、连线落节点本体、组级联删+Ctrl+Z；复制粘贴/一键布局按钮在双主题 hover/激活态可辨。

## C6 · D 类合并 ② VideoGenView.vue（1 文件）

- **目标**：同 C5。保留 5 个 commit 的逻辑：附属模型表单（Chunk G）、全局默认视频模型兜底、分辨率 6 档、参考图 30MB 口径（KIND_LIMIT_LABEL 单源）、画布粘贴联动。
- **动作**：同 C5 合并法。frontupdate 样式 hunk 12 个（页头 3-27 行区间 6 个 + 散点 272/358/440/464a + 样式段 1348-1364c、1666d）；frontend 逻辑 hunk 11+ 个（模板 45-322 区间 7 个 + script 447-577 区间起 4+ 个）。冲突裁决同 C5。
- **涉及文件**：1（`VideoGenView.test.ts` 为 C 类保留件，不动，作为合并正确性的主要自动防线）。
- **依赖**：C1、C3。
- **验证**：`VideoGenView.test.ts` 全绿（含 Chunk G 断言）；vue-tsc 净；手测：选附属模型→表单段联动；不选模型→走全局默认；参考图>30MB 拦截提示三行口径一致。

## C7 · D 类合并 ③ PricingConfigView.vue + 测试（2 文件）

- **目标**：同 C5。保留：分辨率 6 档（RB）、SECOND 秒价分档（RC）。
- **动作**：同 C5 合并法（frontupdate 样式 hunk 5 个：页头 3c/5-12c/20a + 散点 171c/176a；frontend 逻辑 hunk 10 个，集中在 106-482 行：字典、列定义、modal、sanitize）。合并后把 frontupdate 版 `PricingConfigView.test.ts` 落入；若其断言与合并后行为冲突，修测试适配合并后行为（禁反向砍逻辑）。
- **涉及文件**：2。
- **依赖**：C1、C4。
- **验证**：vitest 全绿；vue-tsc 净；手测：模式切 SECOND→秒价分档字段；分辨率下拉 6 档；保存/校验正常。

## C8 · E 类重设计 AuditLogView.vue（1 文件）

- **目标**：审计日志页与其他管理页同构（frontupdate 从未设计过它）。
- **动作**（伪代码）：
  ```
  参照对象 = frontupdate 的 UserManageView（同区管理页标准结构）
  改造 AuditLogView.vue：
    template: 页头换 PageHeader(scene-key='admin')；空态换 InkEmptyState('data')
    style:    全部颜色改语义 CSS 变量（--bg-body/--text-1/...），删硬编码十六进制
    script:   筛选/分页/权限逻辑零改动
  ```
- **涉及文件**：1。
- **依赖**：C1（组件）、C4（参照页已就位）。
- **验证**：vitest 全绿；vue-tsc 净；手测：admin 双主题打开 /admin/logs/audit 正常渲染（对照 frontupdate 快照缺文件的缺陷已不复存在）；非 admin 三重兜底（侧栏隐藏/路由守卫/页内 canView）不回归。
- **无障碍**：表格文字对比度双主题 ≥4.5。

## C9 · F 类主题隐藏 + 存量迁移（3 文件）

- **目标**：对外只剩 夜墨/宣纸；旧主题存量用户平滑落夜墨。
- **动作**（伪代码）：
  ```
  stores/theme.ts:
    ThemeMeta += hidden?: boolean
    THEME_LIST: deep-space/dark-pro/cyber-glow 三项 hidden=true
    新增 visibleThemes = THEME_LIST 中 !hidden 项
    initTheme(): 若 persisted ∈ hidden 集合 → setTheme('ye-mo')（持久化改写）
  components/ThemeSwitcher.vue:
    themeList 数据源 THEME_LIST → store.visibleThemes（原为模块级常量绑定，须改响应式）
  stores/theme.test.ts:
    += 用例：visibleThemes 恰 2 项；persisted='deep-space' 经 initTheme 后 currentTheme='ye-mo' 且 localStorage 已改写；THEME_LIST 仍 5 项（隐藏≠删除）
  ```
- **涉及文件**：3。
- **依赖**：C2~C8 全部完成（终态收口）。
- **验证**：vitest 全绿；vue-tsc 净；手测：选择器仅 2 项；localStorage 手写 `app_theme=deep-space` → 刷新 → 夜墨生效且键值变 `ye-mo`；删 hidden 标志可整体回滚（回滚演练做一次）。
- **联动验收**（联动点 L3）：恢复任一旧主题对外可见后，naive-overrides LEGACY_PRIMARY 分支即时生效。

## C10 · 终验（零新代码）

- **目标**：PRD §7 八条验收逐项打勾。
- **动作**：`vitest run` 全量 + `vue-tsc` + `npm run check:contrast` + `npm run build`（记录 dist 体积对比）+ 起全栈走 testing_strategy §4 双主题 18 项清单（人工 ⚑）。
- **涉及文件**：0。
- **依赖**：C9。
- **验证**：验收记录写入 `workflow_output/` 问题单惯例位置；任何 P0 项归零后才算出口。

---

## 技术坑点预判（本栈 specifics）

| # | 坑 | 场景 | 规避 |
|---|----|------|------|
| 1 | **CRLF 行尾噪声** | 两目录行尾不一致，`diff` 不加 `--strip-trailing-cr` 会把 70 文件虚报 223；复制文件后 git diff 可能整文件红 | 一切比对强制 `--strip-trailing-cr`；`core.autocrlf=true` 已配置，提交时自动归一，复制后 `git diff --stat` 抽查 sane 即可 |
| 2 | **pathspec 陷阱** | cwd 在 `agent-platform/` 时，`git diff <base> -- agent-platform/frontend/x` 双前缀静默匹配空（本次勘察实测踩中） | 统一用相对 cwd 的 `frontend/x`，或 `git -C e:/workspace` + 全路径 |
| 3 | **测试先于实现变红** | B 类测试整文件替换，若组件本体是 D 类合并件（PricingConfigView），测试落地早于合并完成 → 中间态红 | 测试与组件版本配对落地：PricingConfigView.test.ts 移入 C7 |
| 4 | **avif 兼容线** | 场景图只 glob avif（无运行时回退），Safari <16.4 黑块 | ModuleScene 内建 CSS 山形渐变兜底（ridgeGradients），avif 加载失败即显示渐变——走查项 18+P1 Safari 抽查覆盖；不改代码 |
| 5 | **Vite dev watcher** | 50MB 资产目录在 Windows 上拖慢 HMR 文件监听 | 观察到慢再 `server.watch.ignored` 加 art 目录（后续再说，不预做） |
| 6 | **font preload 404** | index.html preload 引用 /fonts，若 C1 复制遗漏 fonts 目录则控制台报错+字体回退 | C1 验证步骤显式包含「dev server 无新增告警」 |
| 7 | **主题中间态** | C2 后默认 ye-mo 但旧主题仍可选、存量旧值用户照常渲染旧主题 | 接受中间态（scss 全量导入保证可渲染），C9 收口迁移 |
| 8 | **ThemeSwitcher 非响应式绑定** | `const themeList = THEME_LIST` 是模块级快照，改 store 后不随 hidden 变化 | C9 强制改为 store computed/getter |
| 9 | **import.meta.glob 相对路径** | ModuleScene 内 glob 路径相对组件文件，复制时目录层级必须一致 | C1 按原树整体复制（已保证），禁止单文件挪位 |
| 10 | **vue-flow 画布自动化失准** | 走查/回归用 Playwright 操作画布时 fitView zoom≠1、框选 Full 包含等四坑 | 见 memory + testing_strategy §5，脚本按既有经验写 |

## 安全检查清单（对照 PRD §6.4，P3 逐项验证）

- [x] api/** 与 stores 的 WS 鉴权/单飞刷新逻辑零变更（C 类文件零触碰，C2~C9 后 `git diff --stat` 确认 22 个 C 类文件不在改动列表）；
- [x] 零新增 npm 依赖（package.json diff 仅 scripts +1 行）；
- [x] `public/fonts/OFL-LXGW-WenKai.txt` 许可文件随包存在（C1 验证）；
- [x] 不引入外链 CDN 资源（字体/插画全自托管，grep 新文件无 `http(s)://` 引用外域）；
- [x] 不动 .env / 鉴权流 / 权限码；审计页三重兜底回归（C8 验证）。

## 功能联动点清单（正向必漏 bug 清单）

| # | 触发动作 | 联动对象 | 预期变化 | 边界（反向/半选/取消/批量） |
|---|----------|----------|----------|------------------------------|
| L1 | ThemeSwitcher 选主题 | data-theme 属性 + naive overrides + localStorage | 全站即时换肤无刷新 | 反向：切回旧值仍可渲染（C9 前）；C9 后旧值不可选；持久键 `app_theme`，logout 不清除（已核实 clearAuthStorage 只清 3 auth 键） |
| L2 | data-theme 切 夜墨↔宣纸 | ModuleScene 场景图变体 | 暗主题载 scene-x.avif、亮主题载 scene-x-light.avif | 缺 -light 变体的模块回退 CSS 山形渐变（不白块）；已挂载页面切换时图即时更换 |
| L3 | theme.ts hidden 标志 | ThemeSwitcher 列表 | 旧 3 项消失，仅剩 2 项 | 恢复=删标志一行；存量 localStorage 旧值经 initTheme 迁移为 ye-mo 并改写持久化 |
| L4 | 无 system:audit:read 权限 | 侧栏项/路由守卫/页内 canView | 审计入口三重不可见/拒绝 | admin 有权限正常进；C8 改版不得破坏三重兜底任一层 |
| L5 | VideoGen 选附属视频模型 | capability 表单段（分辨率/时长/参考图行） | 表单段随模型能力显隐 | 不选模型→全局默认兜底；切换模型→表单重置口径；合并保真点 C6 |
| L6 | Pricing 计费模式切 SECOND | 秒价分档字段/分辨率字典 | 字段按模式显隐，6 档可选 | 保存时 sanitize 按模式裁剪未知档；合并保真点 C7 |
| L7 | 画布点选组→拖组缘端口 | 组边伪 id 边/下游节点透明度 | 组整体对外拉线、下游强调 | 反向：Ctrl+Z 恢复级联删；点组成员≠点组（半选防误判，VIII P4 已修）；合并保真点 C5 |

## 运维考量清单（7 类逐条落字）

| 类 | 决策 | 说明 |
|----|------|------|
| 可观测性 | **不做**（后续再说） | 纯前端改版，无新关键路径；前端错误上报体系另立项。现有 console.warn（断路豁免等）随 C 类文件原样保留 |
| 配置开关 | **做** | 主题 hidden 标志即运行时开关（出问题翻标志即回滚视觉入口，不用回滚发版）；C9 含回滚演练 |
| 可回滚 | **做** | 每 Chunk 独立 commit（C0 建分支 feat/gsl-port）；无 DB 变更、无迁移脚本，git revert 即整体回滚；frontupdate 目录全程不动（天然备份） |
| 限流/熔断/降级 | **不做** | 无新增第三方调用；字体/资产自托管正是消除 CDN 单点；场景图 avif 失败有 CSS 渐变降级路径（坑 4） |
| 运维入口 | **做** | `npm run check:contrast`（对比度巡检）+ `scripts/subset-font.py`（文案加字后字体再子集化）两个脚本入口，用法写入 file_structure.md |
| 告警阈值 | **不做** | 无监控面变化（纯前端）；若后续上前端监控，dist 体积增量阈值建议 <2MB（PRD §6.1 已定基线） |
| 容量/性能预案 | **后续再说** | 资产按需加载已做（glob 懒加载）；50MB 仓库体积已用户拍板保留，git 变慢时再议 LFS/瘦身（记录在案，不预做） |

## 术语表

| 术语 | 大白话 | 案例 |
|------|--------|------|
| Chunk | 一次独立可验证可提交的最小实施块 | C5 = CanvasView 一个文件的合并 |
| hunk | diff 里的一个连续改动段 | CanvasView frontend 侧 7 个逻辑 hunk |
| 三向合并 | 基准版+两边改动合成一份两边都保留的版本 | base+A 逻辑 hunk 回放进 B 样式底板 |
| 底板 | 合并时作为起点的那个版本 | C5~C7 用 frontupdate 版做底板 |
| FOUC | 首屏一帧错误样式再跳对 | 主题初始化晚于首渲染会闪旧色 |
| avif | 新一代图片格式，同画质更小 | 30KB 山景图 |
| dist | npm run build 的产物目录 | webp/png 不应出现在其中 |
| hidden 标志 | 主题元数据上的布尔字段，控制选择器是否展示 | deep-space hidden=true 即对用户不可见 |
| LEGACY_PRIMARY | 旧 3 主题在 naive 覆盖层的最小主色映射 | 回滚演示时旧主题按钮仍是对应主色 |

---

## 附录 A · 执行时点分类快照（C0 填写）

- 快照时间：2026-08-30 21:50；HEAD：`e3968b6a`；分支：`feat/gsl-port`（自 beifen 派生）
- 基线：vitest 969/969 绿（124 文件，95s）；vue-tsc 零错误
- 重跑分类：STYLE=45 / DRIFT=22 / MERGE=3，与规格清单逐文件 diff 零漂移 ✓
- A 类：84（src 内 78 + src 外 6）
- B 类：45（其中 PricingConfigView.test.ts 移 C7）
- C 类：22（零触碰，终验 `git diff --stat` 核对）
- D 类：3（C5/C6/C7）
- E 类：10（C8 实改 AuditLogView 1 个，其余 9 个为逻辑件零触碰、走查覆盖）
- 清单外白名单：ThemeSwitcher.vue（C9）、package.json（C1）、index.html（C1）
