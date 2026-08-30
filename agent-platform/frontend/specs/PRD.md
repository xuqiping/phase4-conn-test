# PRD — 「高山流水」设计系统移植：frontend 双主题切换

> 版本 v1.0 · 2026-08-30 · 唯一真相源。后续实现与本文件冲突时：要么改实现，要么改本文件并记录原因。
> 配套文档：[architecture.md](architecture.md)（文件五分类清单=范围真值源）、[testing_strategy.md](testing_strategy.md)、[file_structure.md](file_structure.md)。
> 设计审美真值源：`agent-platform/workflow_output/06_UI艺术与视觉资产/`（ART-DIR-0001 定稿等 11 篇）。

## 1. 背景与问题

`agent-platform/frontupdate/` 是 frontend 的完整副本 + 「高山流水」中国风设计系统重做（基准 commit `1aff8734`）。此后 frontend 又前进了 **19 个 commit**（画布组边/复制粘贴/一键布局、WS 鉴权加固、视频模型 Chunk G、全局默认视频模型、计费 V164/165 等），frontupdate 未同步。

当前矛盾：

1. frontend 功能最新但视觉仍是旧三暗色主题；frontupdate 视觉最新但逻辑落后 19 个 commit，且自身带缺陷（router 引用 `AuditLogView.vue` 但文件缺失，访问审计日志页运行时崩）。
2. 设计系统提供 5 主题（旧 3 + 宣纸/夜墨），业务决策只保留**宣纸（亮）/ 夜墨（暗）**对外可见，旧 3 套隐藏保留代码。
3. frontend 独有的新增内容（审计日志页、画布新功能界面元素）未经过新风格设计。

## 2. 目标

把 frontupdate 的设计系统**移植进 frontend**（方向：frontend ← frontupdate，不反向），实现：

1. frontend 全站呈现「高山流水」双主题（夜墨默认 + 宣纸可选），保留全部既有功能与 19 个 commit 的修复；
2. 旧 3 主题（deep-space / dark-pro / cyber-glow）从主题选择器隐藏，代码与样式文件保留（可随时翻开关恢复）；
3. frontend 独有内容按新风格全套对齐（PageHeader / ModuleScene / InkEmptyState / 设计令牌）；
4. 全程测试绿：vitest 全量 + vue-tsc 净 + WCAG 对比度校验脚本绿。

## 3. 非目标（Out of Scope）

- 不改任何后端代码、API 契约、数据库（**无 db_schema**，本 PRD 即存储层面的完整声明）；
- 不删除旧 3 主题的 scss/主题定义（只隐藏入口）；
- 不做移动端适配（桌面优先，最低宽度 1280px，沿袭现状）；
- 不做国际化（仅中文界面）；
- 不新增业务功能、不重写任何业务逻辑；
- 不追求 CanvasBoard 等大组件的逐行手工重设计（frontupdate 对它们也只靠设计令牌系统换肤，见 architecture §4 DRIFT_ONLY 说明）；
- 不清理 frontupdate 目录（保留作参考素材库，处置另议）。

## 4. 用户故事

| # | 角色 | 故事 | 验收要点 |
|---|------|------|----------|
| US-1 | 普通用户 | 打开平台任意页面，看到夜墨主题（黛蓝墨夜+天青点缀），切换到宣纸主题（宣纸月白+墨色正文）全站即时生效并持久记忆 | 切换无刷新、无闪白；刷新/重开保持；登录页也生效 |
| US-2 | 普通用户 | localStorage 里存的还是旧主题（如 deep-space）时，平滑落到夜墨，不白屏不报错 | 首次加载即夜墨；旧值被改写持久化为 ye-mo |
| US-3 | 管理员 | 访问审计日志页（/admin/logs/audit），页面与其他管理页同构：山景横幅+诗签+新风格表格/空态 | 页面正常渲染（frontupdate 快照缺该文件的缺陷在移植后不复存在）；对比度达标 |
| US-4 | 重度画布用户 | 用新主题使用画布全部新功能（复制粘贴/一键布局/组边拉线/媒体限制提示），视觉与全站统一，无旧风格残留、无看不清的元素 | 新增按钮/浮层/hover 态走令牌系统；文字对比度 ≥4.5 |
| US-5 | 视频生成/定价管理用户 | 视频生成页、定价配置页在保留全部新功能（Chunk G、全局默认模型、SECOND 分档计价）的前提下呈现新视觉 | 三向合并零功能回归（见 architecture §5） |
| US-6 | 前瞻用户 | 系统偏好「减少动态效果」时，装饰动效关闭 | prefers-reduced-motion 全局降级生效 |
| US-7 | 开发者 | 主题/令牌/场景注册表有单一真值源，新增页面按 registry 模式接入即可获得同构视觉 | file_structure.md 指引清晰；check-contrast 可本地跑 |

## 5. 功能需求

### FR-1 设计系统搬入（frontupdate 独有：src 内 78 个文件 + src 外 `scripts/` 3 个 + `public/fonts/` 3 个）

搬入内容与用途：

| 层 | 文件 | 用途 |
|----|------|------|
| 原语层 | `styles/tokens-ink.scss` | 传统色板 16 色 + 书法展示字体 `@font-face` + 云雾缓动 + reduced-motion 降级 |
| 主题层 | `styles/themes/ye-mo.scss`、`xuan-zhi.scss` | 原语 → 语义 CSS 变量映射（暗/亮两套） |
| 组件接管层 | `styles/naive-overrides.ts` | 按 `data-theme` 生成 Naive UI `GlobalThemeOverrides`（宣纸走亮色基座 null，夜墨走 darkTheme） |
| 纹理层 | `styles/texture.scss` | 现代意境工具类（雾层/展示字体类，装饰 opacity ≤0.12 红线） |
| 场景层 | `config/scenes.ts` + `components/ModuleScene.vue` | 12 模块山景注册表（专属色+诗签+山形种子四档轮转）+ 渲染组件 |
| 骨架组件 | `components/PageHeader.vue`、`components/InkEmptyState.vue` | 页头（标题+场景）与空态（插画+短语）标准件 |
| 静态资产 | `src/assets/art/**`（50MB 三格式）、`public/fonts/**`（45KB 霞鹜文楷子集 + OFL 许可） | 24 幅模块场景（12 模块×双主题）、空态/登录/品牌/工作台插画、展示字体 |
| 工具 | `scripts/subset-font.py`、`check-contrast.mjs`、`font-glyphs.txt` | 字体再子集化管线 + WCAG 对比度校验 |

搬入时保持 frontupdate 版本原样（含注释与红线说明），不做「顺手优化」。

### FR-2 双主题接入与样式覆盖（45 个纯样式文件）

App.vue 接 Naive 主题基座计算（`getNaiveBaseTheme`/`getNaiveOverrides`，随 `currentTheme` 响应式切换）；main.ts 样式导入顺序变为：原语 → 变量 → 主题（5 套）→ 纹理 → 全局；index.html 增加 45KB 字体 `<link rel="preload">`（swap 不阻塞渲染）。45 个 frontend 未改动、frontupdate 已重设计的文件直接采用 frontupdate 版本（完整清单见 architecture §4）。

### FR-3 旧 3 主题隐藏与迁移

- `ThemeMeta` 增加 `hidden?: boolean` 标志，deep-space / dark-pro / cyber-glow 三项置 `hidden: true`；主题选择器（ThemeSwitcher，消费 `THEME_LIST`）过滤隐藏项，对外只剩 夜墨/宣纸 两项。
- scss 文件、`ThemeName` 联合类型、naive-overrides 的 LEGACY_PRIMARY 分支全部保留（隐藏≠删除，恢复=翻一个布尔值）。
- **迁移规则**：初始化时若 localStorage 存的是隐藏主题 → 落到 `ye-mo` 并持久化改写。
- **默认主题**：`ye-mo`（夜墨）。存量用户习惯暗色，过渡最平滑。

### FR-4 frontend 独有内容全套对齐

| 对象 | 动作 |
|------|------|
| `views/admin/logs/AuditLogView.vue` | 接入 PageHeader + admin 场景（ModuleScene，scene-admin，诗签「居高声自远」）+ InkEmptyState + 新令牌配色；保持筛选/分页/权限逻辑不动。同时修复 frontupdate router 引用缺文件的问题（保留 frontend 版路由与文件即自动修复） |
| 画布新功能界面元素（复制粘贴反馈、一键布局入口、组边拉线/hover 态、媒体限制提示，散布在 CanvasBoard/CanvasView/PropertyPanel 等） | 逻辑不动（frontend 版本为准），视觉走令牌系统自动换肤；对 hover/激活/禁用态做令牌对齐校验，发现旧风格硬编码色值即替换为语义变量 |

### FR-5 三向合并（3 个文件，行为规格）

frontupdate 重设计过、frontend 同时改过逻辑的文件，合并原则：**frontend 的业务逻辑全保留 + frontupdate 的呈现层全采用**。

| 文件 | 必保的 frontend 逻辑（验收基准） | 采用的 frontupdate 呈现 |
|------|--------------------------------|--------------------------|
| `views/VideoGenView.vue` | 视频模型 Chunk G 附属模型表单/结果联动；全局默认视频模型兜底；参考图 30MB 口径 | 新版式/场景/令牌 |
| `views/admin/PricingConfigView.vue` | 分辨率字典 6 档；SECOND 秒价分档（V164）；VIDEO 行协议可选+存量归一（V165） | 新版式/场景/令牌 |
| `views/CanvasView.vue` | 组边伪 id/连线落点直连/快照合并拆分等 VIII A 修复 | 新版式/场景/令牌 |

### FR-6 主题初始化时序

主题初始化收敛到 App.vue 挂载时（`onMounted → initTheme`），覆盖登录页等不经 MainLayout 的路由；MainLayout/LoginView 既有 initTheme 调用保留（幂等）。目标：任意入口首屏无旧主题/无样式闪跳（FOUC——首次渲染一帧错误样式的闪烁）。

## 6. 非功能需求

### 6.1 性能目标

| 指标 | 目标值 | 验证方式 |
|------|--------|----------|
| 展示字体 | 45KB woff2，preload + font-display:swap，不阻塞首渲染 | index.html 检查 + Network 面板 |
| 场景插画 | avif 单幅 10~30KB，经 `import.meta.glob` 动态 import 按路由懒加载；首屏最多 1 幅 | 打包产物 chunk 分析 |
| 主题切换 | CSS 变量 + data-theme 属性切换，感知耗时 <100ms，无整页刷新 | 手测秒表/Performance 面板 |
| 构建产物增量 | webp/png 等未引用格式不进 dist（仓库内保留）；dist 增量主要来自被引用的 avif 与字体，预期 <2MB | 构建前后 dist 体积对比 |
| 回归底线 | 不引入新的同步阻塞渲染资源；vitest 全量通过时间较基线漂移 <20% | CI/本地计时 |

### 6.2 兼容性

- 浏览器：Chrome / Edge / Firefox 最新两版 + Safari 16.4+（avif 支持线）。不做旧浏览器回退（沿袭 frontupdate 决策：场景图仅 glob avif）。
- 桌面优先，最低宽度 1280px。

### 6.3 可访问性（ART-QA-0001 红线，硬性）

- 文字对比度：正文 ≥4.5:1，大字/图标 ≥3.0:1，双主题全覆盖，`node scripts/check-contrast.mjs` 绿（接入 `npm run check:contrast`）；
- `prefers-reduced-motion: reduce` 时全部装饰动效关闭（tokens-ink.scss 全局降级已有，不得移除）;
- 装饰层 opacity ≤0.12 且永远压在文字层之下；
- 展示字体仅用于标题/空态短句（每屏 ≤1 处），正文禁用。

### 6.4 安全

- **零新增 npm 依赖**（frontend 与 frontupdate 的 package.json 唯一差异是 frontend 多 `@dagrejs/dagre`，保留 frontend 版）；无供应链面扩大。
- 无 API 调用、鉴权、数据流变更；`api/**` 层的 22 个漂移文件一律取 frontend 版本（含 WS 4401 单飞刷新、`_background` 断路豁免等安全修复），frontupdate 版本一律不采用。
- 字体许可：霞鹜文楷 SIL OFL 1.1，`public/fonts/OFL-LXGW-WenKai.txt` 许可文件必须随包分发（已含，搬入时不得遗漏）。

## 7. 验收标准（出口条件）

1. vitest 全量绿（基线 969 个，移植后以移植后总数为准、零 skip 新增）+ `vue-tsc` 零错误；
2. `npm run check:contrast` 绿（双主题全色对）；
3. 主题选择器仅显示 夜墨/宣纸；切换即时生效、刷新保持；旧主题存量值自动迁移到夜墨；
4. 全部路由双主题走查无旧风格残留、无对比度不达标文字（人工，清单见 testing_strategy §4）；
5. 三向合并文件功能回归通过（VideoGen/Pricing/Canvas 业务行为，testing_strategy §3）；
6. 审计日志页正常渲染且与其他管理页同构；
7. 画布全部新功能在双主题下可用且视觉统一；
8. 构建产物体积符合 §6.1 目标。

## 8. 冲突裁决

- 本 PRD 与 `workflow_output/06_UI艺术与视觉资产/` 设计文档冲突：**设计审美问题以 06 目录最新定稿为准，工程接入问题以本 PRD 为准**；
- 本 PRD 与 frontupdate 代码内注释（DESIGN-TOKEN-0001 等编号引用）冲突：以编号指向的 06 目录原文档为准。

## 9. 术语表

| 术语 | 大白话 | 简单案例 |
|------|--------|----------|
| 设计令牌（design token） | 把颜色/字体/圆角等样式原子起名集中管理的基础变量 | `--ink-tianqing: #8FBCD4`，组件只引用名字不写死色值 |
| 主题（theme）/ 皮肤 | 同一套界面换一套颜色的方案 | 夜墨=暗色系，宣纸=亮色系 |
| CSS 变量（CSS custom property） | 浏览器原生支持的样式变量，改一个值全站生效 | `data-theme="ye-mo"` 时 `--bg-body` 变成黛蓝 |
| GlobalThemeOverrides | Naive UI 提供的主题覆盖入口，用 JS 对象改组件库默认配色 | 把按钮主色改成天青 #8FBCD4 |
| FOUC | 首屏一帧用错样式再跳对的「闪一下」 | 刷新页面瞬间白屏/旧色闪现 |
| avif | 更新一代图片压缩格式，同画质体积比 png/webp 小 | 30KB 的山景插画 |
| 字体子集化 | 只保留用到的那几百个字形，字体文件从几 MB 瘦到几十 KB | 45KB 的霞鹜文楷展示字体 |
| `import.meta.glob` | Vite 的批量懒加载语法，按文件名模式把一堆文件变成按需加载函数 | 进哪个页面才加载哪幅场景图 |
| WCAG 对比度 | Web 无障碍标准里的文字可读性指标，4.5:1 起算合格 | 月白字 #DFE7EE 压黛蓝底 #151D29 |
| prefers-reduced-motion | 用户系统级「减少动画」偏好，网页应尊重并关闭动效 | 前庭障碍用户关掉云雾缓动 |
| 三向合并 | 基准版 + 两边各自改动，人工合成一个两边都保留的版本 | base+A 逻辑+B 样式 → 新文件 |
| OFL | SIL 开源字体许可证，可免费商用但需随附许可文件 | 霞鹜文楷的 OFL-LXGW-WenKai.txt |
