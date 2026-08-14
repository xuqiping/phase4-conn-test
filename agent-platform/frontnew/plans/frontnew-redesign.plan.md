# plan — frontnew 前端样式优化版

> 版本 v1.0 · 2026-08-14 · 依据 `specs/` 全部规格。只含伪代码。P3 逐 chunk 实现，每 chunk 完成即验证。

## 0. chunk 总览

| # | chunk | 依赖 | 涉及文件数 |
|---|-------|------|-----------|
| C1 | 工程骨架 + 主题系统底座 | — | 10 |
| C2 | 4 主题 tokens + Naive overrides + 切换器 | C1 | 8 |
| C3 | 主布局（侧边栏/顶栏） | C2 | 4 |
| C4 | 画布：节点基座 + 6 节点 + 连线 + 演示流 | C2 | 12 |
| C5 | 对话页 | C3 | 4 |
| C6 | 智能体大厅 | C3 | 3 |
| C7 | 工作流列表 | C3 | 3 |
| C8 | 自动化测试 + 压测 + 构建验证 | C4–C7 | 6 |

---

## C1 工程骨架 + 主题系统底座

**目标**：`pnpm dev` 起空壳应用，路由可跳，主题切换管线通（先只有占位主题色）。

**动作（伪代码）**：
1. 写 `package.json`：依赖版本对齐 frontend（vue 3.4 / vite 5 / naive-ui 2.38 / pinia / vue-router / @vue-flow/core+background / @vicons/ionicons5 / sass / typescript / vitest 全家）；**不装** axios/crypto-js/dingtalk-jsapi
2. `vite.config.ts`：vue 插件 + `@` → `/src` alias + sass 支持
3. `tsconfig.json`：抄 frontend 关键配置（strict、paths）
4. `index.html`：内联一段阻塞小脚本——**首帧前**从 localStorage 读主题写入 `<html data-theme>`，防主题闪烁（FOUC）
5. `main.ts`：createApp → pinia → router → mount
6. `App.vue`：`<n-config-provider :theme-overrides>` 绑定 store 派生值 + `<n-message-provider>` 壳
7. `stores/theme.ts`：state=当前主题名；action `setTheme(name)` = 写 `document.documentElement.dataset.theme` + localStorage + state；init 时恢复
8. `theme/themes.ts`：4 主题元信息表（key/中文名/预览色/描述）
9. `theme/useTheme.ts`：组合式函数包 store + 返回 naiveOverrides computed（C2 填真值，先返回空对象）
10. `router/index.ts`：5 路由 + 默认重定向 `/canvas` + NotFound；无守卫；顶栏常驻「样式预览版」徽标

**涉及文件**：package.json, vite.config.ts, tsconfig.json, index.html, src/main.ts, src/App.vue, src/stores/theme.ts, src/theme/themes.ts, src/theme/useTheme.ts, src/router/index.ts

**验证**：`pnpm dev` 起服务；5 路由手动访问均渲染占位文字；`localStorage` 写入/读取主题键正常。

---

## C2 4 主题 tokens + Naive overrides + 切换器

**目标**：4 主题真实生效，切换器可点，全站 CSS 变量管线打通。

**动作（伪代码）**：
1. `theme/tokens/base.css`：共享变量（圆角/时长/间距/字号/缓动/表面层级变量名声明默认值）
2. 按 design_system.md §3–6 写 4 个主题 css：`neon-pulse.css / calm-slate.css / hybrid-glow.css / cineon.css`，每个 = `:root[data-theme='x'] { 全量变量 }`；T4 额外 `--accent-2`
3. `theme/naive.ts`：导出 `NAIVE_TOKENS: Record<主题key, 色值对象>`——**与 css 文件同源的 JS 镜像**，生成 naive `GlobalThemeOverrides`（common 色板 + Button/Card/Input/Tag/DataTable 主色）
4. `useTheme.ts` 补上：naiveOverrides = computed(NAIVE_TOKENS[当前主题])
5. `components/app/ThemeSwitcher.vue`：下拉，4 项各带 2~3 个预览色点 + 名称；点击调 `setTheme`
6. `styles/global.scss`：reset、滚动条皮肤（var 驱动）、`::selection`、`prefers-reduced-motion` 下全局关动画的规则
7. 无障碍：切换器键盘可达（n-dropdown 原生支持）；每主题正文/--tx-1 对 --sf-0 对比度在写值时自查 ≥4.5:1（用 DevTools 对比度工具）

**涉及文件**：tokens/base.css, tokens/neon-pulse.css, tokens/calm-slate.css, tokens/hybrid-glow.css, tokens/cineon.css, theme/naive.ts, theme/useTheme.ts, components/app/ThemeSwitcher.vue, styles/global.scss

**验证**：页面临时放切换器，4 主题切换可见底色/强调色变化；无闪烁；reduced-motion 开关联动生效。

---

## C3 主布局（侧边栏/顶栏）

**目标**：MainLayout 骨架完成，5 页共享；4 主题下形态正确。

**动作（伪代码）**：
1. `layouts/MainLayout.vue`：左 Sidebar + 右列（TopBar + `<router-view>`）；侧边栏折叠态存 pinia 或本地 ref（简单起见本地 ref + provide）
2. `components/app/Sidebar.vue`：菜单项静态数组（画布/对话/智能体/工作流）；激活态 = 当前路由；折叠时只留图标；样式全走 var（`--sf-1` 底、激活项 accent 指示条）
3. `components/app/TopBar.vue`：左面包屑（路由 meta.title）+ 右 ThemeSwitcher + 通知铃铛（mock 红点）+ 头像（首字母圆块）
4. T1 主题侧边栏玻璃拟态（blur 14px）；T2 纯平面；T4 暖黑
5. 无障碍：折叠钮 `aria-label`；菜单 `nav` 语义标签；焦点环可见（outline 用 accent）

**涉及文件**：MainLayout.vue, Sidebar.vue, TopBar.vue,（复用 ThemeSwitcher.vue）

**验证**：折叠/展开无布局跳变；4 主题逐个切，侧栏/顶栏对比度可读；Tab 键可达所有顶栏控件。

---

## C4 画布：节点基座 + 6 节点 + 连线 + 演示流【重中重】

**目标**：无限画布完整呈现 6 类型 × 状态矩阵新设计。

**动作（伪代码）**：
1. `mocks/types.ts`：`CanvasNodeStatus = 'idle'|'running'|'success'|'failed'`（对齐 frontend）；节点/连线/智能体等 mock 类型
2. `mocks/canvas.ts`：演示工作流生成器——手写 12 节点样板（6 类型 × 覆盖 4 状态，含连线成链：脚本→分镜→图像→视频主干 + 文本/音频旁支）；另导出 `genStressNodes(n)` 压测生成器（网格布局摆 n 个节点）
3. `components/canvas/NodeCardBase.vue`：**props 对齐 frontend 现有 CanvasNodeBase**（kind/kindLabel/label/status/selected/assetBadge 中除 assetBadge 外全保留，assetBadge 一期不做——mock 无资产体系）；结构 = 头部（类型色条/图标/kindLabel/label/#SC 序号徽标/状态点）+ `<slot />` 内容区 + 底部状态条（状态文案+mock 耗时/token 徽标）+ Handle 上入下出；`defineOptions({inheritAttrs:false})`（沿用 frontend 防 $attrs 覆盖的坑）；状态→类名映射，零内联样式
4. 6 个节点组件：Text/Image/Video/Audio/Script/Storyboard，各自内容区按 design_system §7.2（缩略图=渐变 div 占位，不引图片；波形=div 条形组）
5. `components/canvas/edges/FlowEdge.vue`：贝塞尔 + 选中时中点删除钮；颜色 var 驱动，选中取源节点类型色
6. `components/canvas/CanvasBoard.vue`：VueFlow 容器——`only-render-visible-elements`（性能）、小地图（节点按类型着色；T4 时间轴外观）、背景网格点；读 URL `?nodes=100` 时改用压测数据
7. `components/canvas/CanvasToolbar.vue`：缩放 +/-、适应视图、节点计数；样式 var 驱动
8. `views/CanvasView.vue`：CanvasBoard + 右侧属性面板壳（显示选中节点名/类型/状态，纯展示）
9. 状态动效：running = T1/T3 conic 流光（伪元素 rotate，**不动 gradient 角度**）、T2 状态点呼吸（opacity）、T4 底部播放头 translateX；全部 `prefers-reduced-motion` 下关闭

**涉及文件**：mocks/types.ts, mocks/canvas.ts, NodeCardBase.vue, nodes/TextNode.vue, nodes/ImageNode.vue, nodes/VideoNode.vue, nodes/AudioNode.vue, nodes/ScriptNode.vue, nodes/StoryboardNode.vue, edges/FlowEdge.vue, CanvasBoard.vue, CanvasToolbar.vue, views/CanvasView.vue

**验证**：演示流 12 节点全渲染；6 类型预览区形态各异；4 数据状态视觉符合 §7.3 矩阵；选中节点右面板同步；`?nodes=100` 缩放流畅；4 主题切换卡片皮肤全换。

---

## C5 对话页

**目标**：ChatView 新皮肤 + mock 流式效果。

**动作（伪代码）**：
1. `mocks/chat.ts`：2 会话；消息含 纯文本/代码块/引用卡 三种
2. `components/chat/MessageList.vue`：用户右/AI 左；AI 消息带头像与「AI」徽标
3. `components/chat/MessageBubble.vue`：气泡 + 代码块（等宽字体+复制钮占位）+ 引用卡（左边框 accent）
4. `components/chat/ChatInput.vue`：输入框 + 附件占位钮 + 发送钮；Enter 发送 mock = setTimeout 逐字打出预设回复（流式观感）
5. `views/ChatView.vue`：左会话列表（窄栏）+ 右消息区

**涉及文件**：mocks/chat.ts, MessageList.vue, MessageBubble.vue, ChatInput.vue, views/ChatView.vue

**验证**：发消息出现流式回复；代码块等宽；4 主题下气泡对比度达标；reduced-motion 时流式改为整段直出。

---

## C6 智能体大厅

**动作（伪代码）**：
1. `mocks/agents.ts`：12 智能体（名/描述/标签/用量数）
2. `components/agent/AgentCard.vue`：头像（渐变首字块）/名/描述 2 行截断/标签 n-tag/底部用量等宽数字；hover 浮起
3. `views/AgentHallView.vue`：顶部搜索框 + 标签筛选行（前端内存过滤）+ 卡片网格（auto-fill minmax 260px）

**涉及文件**：mocks/agents.ts, AgentCard.vue, views/AgentHallView.vue

**验证**：搜索/标签过滤即时生效；空结果有占位；4 主题卡片皮肤正确。

---

## C7 工作流列表

**动作（伪代码）**：
1. `mocks/workflows.ts`：10 条（名/状态/更新人/耗时/更新时间）
2. `components/workflow/WorkflowStatusTag.vue`：草稿/运行中/完成/失败 4 徽标，色=语义色
3. `views/WorkflowListView.vue`：n-data-table（列：名称/状态/节点数/最近运行/耗时/操作占位钮）；顶部「新建工作流」主钮（占位）

**涉及文件**：mocks/workflows.ts, WorkflowStatusTag.vue, views/WorkflowListView.vue

**验证**：表格 4 主题可读（DataTable overrides 在 C2 已配）；状态徽标语义色正确；行 hover 态有反馈。

---

## C8 自动化测试 + 压测 + 构建验证

**动作（伪代码）**：
1. vitest 配置 + 6 组用例（对应 testing_strategy §1）：主题切换持久化 / tokens 完整性（读 4 个 css 断言必需变量齐全 + **naive.ts 镜像与 css 值一致性**，防双源漂移）/ NodeCardBase 状态类名 / 6 节点类型类名 / mock 演示流连线无悬空 / 5 路由渲染冒烟
2. `?nodes=100` 人工手感 + Performance 面板录帧率
3. `pnpm build && pnpm preview` 走查一遍
4. 修复测试暴露的问题

**涉及文件**：vitest.config.ts, tests/theme.spec.ts, tests/tokens.spec.ts, tests/node-card.spec.ts, tests/nodes.spec.ts, tests/canvas-mock.spec.ts, tests/routes.spec.ts

**验证**：`pnpm test` 全绿；构建成功且 preview 与 dev 一致。

---

## 技术坑点预判（具体到库与场景）

| 坑 | 场景 | 规避 |
|----|------|------|
| **tokens 双源漂移**：css 文件与 naive.ts JS 镜像各存一份色值，改一个忘另一个 → 组件库与自写组件两个色 | 任何改色时 | C8 写一致性单测：解析 css 变量值 vs naive.ts 对象值逐键比对；PR 级拦截 |
| **backdrop-filter blur 性能**：T1 若给 100 个节点卡片都 blur，GPU 合成层爆炸、缩放掉帧 | `?nodes=100` | 节点卡片**不用 blur**（只半透明底+辉光）；blur 只给侧边栏/弹层这类 ≤3 个的固定面板。design_system §3 以此为准实现 |
| **conic-gradient 角度动画兼容性**：直接动 gradient 角度需 `@property`，旧 Edge 不支持 → 动画失效或闪烁 | T1 running 节点 | 用伪元素 `rotate` 动画（transform，全绿兼容），渐变本身静止 |
| **Vue Flow `$attrs` 透传**：`label:undefined` 覆盖显式 prop（frontend 已踩过） | 6 节点组件 | 全部 `defineOptions({inheritAttrs:false})`，照抄 frontend 注释 |
| **Vue Flow 大画布掉帧**：100 节点默认全量渲染 | `?nodes=100` | 开 `only-render-visible-elements`；动效只动 transform/opacity；节点内容区避免 blur/大图 |
| **Naive DataTable 暗色**：overrides 漏配表头/边线色 → T1/T4 下表格露浅色底 | C7 | C2 overrides 显式含 DataTable；C7 验证项含 4 主题走查 |
| **首帧主题闪烁（FOUC）**：JS 挂载后才设 data-theme → 白底闪一下 | 任何刷新 | index.html 内联阻塞脚本首帧前读 localStorage 设属性 |
| **CSS 变量不进 SVG 属性**：vue-flow 小地图颜色 prop 要具体色值，var() 不生效 | 小地图 | 从 `getComputedStyle` 读当前主题值传入，主题切换时重算 |
| **localStorage 隐私模式异常**：罕见但会崩首帧 | init | 读包 try/catch，失败落默认主题 |

## 安全检查清单（对照 PRD §5.3）

- [ ] 全项目无 `v-html`（mock 文本走插值）——P3 每 chunk grep 自查
- [ ] localStorage 只写 `frontnew-theme` 一键，无敏感数据
- [ ] 无网络请求代码（grep `fetch|axios|XMLHttpRequest` 应为 0 命中）
- [ ] 依赖版本全部锁在 frontend 已用区间，不引新包
- [ ] mock 数据无真实用户信息（全虚构名）

## 功能联动点清单

| # | 触发动作 | 联动对象 | 预期变化 | 边界 |
|---|---------|---------|---------|------|
| L1 | 切换主题 | ① html[data-theme] ② localStorage ③ naive overrides ④ 小地图颜色 ⑤ 全部 var 驱动样式 | 同步换肤 | **反向**：刷新后恢复选中主题；**异常**：localStorage 存了非法主题名 → 回退默认 T1；切换中再点 → 后者覆盖（无队列） |
| L2 | 折叠侧边栏 | 内容区宽度 + 画布尺寸 | 画布 resize 不留下空白/错位 | 反复快速折叠 → 动画结束后尺寸正确（vue-flow 监听容器 resize） |
| L3 | 选中画布节点 | 右侧属性面板 + 该节点连线 | 面板显示节点信息；相连边变类型色 | **反向**：点空白处取消选中 → 面板回占位文案、边色还原；**批量**：框选多节点 → 面板显示「已选 N 个」 |
| L4 | URL 带 `?nodes=N` | 画布数据源 | 改用压测生成器 | N 缺失/非法 → 用演示流 12 节点；N>500 → 截 500 并 console.warn |
| L5 | 对话页发送消息 | 消息列表 + 输入框 | 追加用户消息 + 流式 AI 回复；发送中输入框禁用 | 发送中再按 Enter → 忽略；reduced-motion → 直出不流式 |
| L6 | 大厅搜索/标签筛选 | 卡片网格 | 内存过滤即时刷新 | 无结果 → 空态占位；清空搜索 → 恢复全量；标签多选为「或」关系 |

## 运维考量清单（「上线后怎么运维」反推）

| 类别 | 决策 | 说明 |
|------|------|------|
| 可观测性 | **后续再说** | 纯本地 mock 无后端；合回 frontend 时再接现有前端监控，本计划不做 |
| 配置开关 | **做** | 支持 `?theme=cineon` URL 参数覆盖默认主题（低成本，方便 4×5 截图走查与分享链接定点看人） |
| 可回滚 | **做（天然）** | frontnew 独立新目录，零改动 frontend；不满意整目录删即回滚 |
| 限流/熔断/降级 | **不做** | 无任何外部依赖与网络请求 |
| 运维入口 | **做** | `?nodes=N` 压测参数即运维/压测入口；`?theme=` 同上 |
| 告警阈值 | **不做** | 无服务可告警 |
| 容量/性能预案 | **做（一档）** | 目标 100 节点 ≥55fps；1000 节点**不做**（超出画布实际规模，规格明确记录此上限） |

## 迭代记录

- v1.0 初版后自检一轮，修正：① 状态枚举对齐 frontend `CanvasNodeStatus`（idle/running/success/failed），规格三处已同步修订；② 连接桩方向改上入下出对齐现有；③ C2 增补「css↔naive.ts 双源一致性」测试入 C8；④ 节点卡片明确禁用 backdrop-filter blur（性能坑表）。

## 出口自核

- [x] 8 chunk，每步含 目标/动作/文件/依赖/验证
- [x] 联动点含反向/批量/异常边界
- [x] 运维 7 类逐条 做/不做/后续再说
- [ ] **等待你明确许可后进 Phase 3**

## 术语表

| 术语 | 大白话 | 案例 |
|------|--------|------|
| FOUC | 无样式内容闪烁：页面先露裸样式再换装 | 刷新时白底闪一下才变暗色 |
| conic-gradient | 绕圆心变色的圆锥渐变 | 运行节点的旋转流光描边 |
| backdrop-filter | 让元素背后内容模糊的滤镜 | 毛玻璃侧边栏 |
| themeOverrides | Naive UI 的组件换肤配置 | 把 Button 主色改成 accent |
| only-render-visible-elements | vue-flow 只渲染可视区节点的开关 | 100 节点不卡 |
| $attrs 透传 | Vue 自动把父组件多余属性挂到根元素 | `label:undefined` 覆盖显式 prop 的坑 |
