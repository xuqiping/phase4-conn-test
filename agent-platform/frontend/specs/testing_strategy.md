# testing_strategy.md — 「高山流水」移植测试策略

> 版本 v1.0 · 2026-08-30。配套：[PRD.md](PRD.md)、[architecture.md](architecture.md)。
> 本项目无桌面客户端；纯 Web 前端改造，不涉后端与存储。

## 1. 测试分层总览

| 层 | 工具 | 门槛 | 性质 |
|----|------|------|------|
| 单元/组件测试 | vitest（既有全量套件，基线 **969 绿**） | 全绿，零新增 skip | 自动 |
| 类型检查 | vue-tsc | 零错误 | 自动 |
| 对比度校验 | `scripts/check-contrast.mjs`（挂 `npm run check:contrast`） | 全色对达标（正文 ≥4.5 / 大字 ≥3.0） | 自动（新增） |
| 三向合并定向回归 | 手工操作 + 既有接口/组件测试 | FR-5 表逐项通过 | 半自动 |
| 双主题视觉走查 | 人工（浏览器） | 全路由清单走完 | **人工** ⚑ |
| 构建产物检查 | `npm run build` + dist 体积对比 | §6.1 性能目标 | 半自动 |

## 2. 自动化测试要点

1. **基线先行**：移植动手前跑一次 `npx vitest run` + `npx vue-tsc` 记录绿色基线数；每个 Chunk 完成后回归同口径。
2. **B 类覆盖带来的测试同步**：45 个文件里含 frontupdate 已更新的测试（`theme.test.ts`、`PricingConfigView.test.ts`、AdminFeedback/AdminHelpArticles/PaymentChannelConfig/AgentDetailView/FeedbackCenterView 的 `.test.ts`），整文件随实现一起替换即可；`theme.test.ts` 须追加 FR-3 用例：隐藏主题不出现在 `visibleThemes`、`initTheme` 对存量旧主题值的迁移改写。
3. **D 类合并后**：`VideoGenView.test.ts`（frontend 版，含 Chunk G 断言）必须全绿——它是三向合并没丢逻辑的主要自动防线。
4. **check-contrast.mjs**：色对清单与 `ye-mo.scss`/`xuan-zhi.scss`/`naive-overrides.ts` 同步维护（token 改值必跑）。接入 package.json scripts 后纳入完成门。
4a. **D 类文件的既有测试**（如 `PricingConfigView.test.ts` 取 frontupdate 版）：若断言与合并后行为（frontend 逻辑优先）冲突，修测试适配合并后行为，禁止反向砍逻辑迁就测试。
5. 不新增 E2E 框架；浏览器验证用既有 Node + Playwright harness（见 §5 环境要点）。

## 3. 三向合并定向回归（FR-5 逐项，人工操作清单）

| 文件 | 操作脚本 | 通过标准 |
|------|----------|----------|
| VideoGenView | 选附属视频模型生成 → 查结果联动；不选模型提交走全局默认；参考图超 30MB 被 | 功能行为与合并前 frontend 一致，视觉为新风格 |
| PricingConfigView | 打开定价管理 → 分辨率 6 档可选；VIDEO 行协议可选；SECOND 秒价分档保存 | 同上 |
| CanvasView | 组拉线直连、连线落节点本体、组级联删 + Ctrl+Z 恢复 | 同上（画布操作细节见 §5 vue-flow 四坑） |

## 4. 双主题视觉走查清单（**人工交互测试** ⚑ P0）

前置：本地起全栈（§5 一键脚本），账号 admin/admin123。每项在 **夜墨 + 宣纸** 两主题各走一遍，切换用右上角 ThemeSwitcher。

| # | 路由/场景 | 检查点 |
|---|-----------|--------|
| 1 | 登录页 | 泼墨插画、品牌印章、字体 preload 后展示字体生效 |
| 2 | 工作台/首页 | 山景横幅、诗签、雾层不压文字（MainLayout 雾图 opacity 0.18 系 frontupdate 原版定值、内容层 z-index 高于雾层；`u-mist-layer` 工具层红线 ≤0.12） |
| 3 | 对话 ChatView | 气泡/代码块/引用块双主题可读 |
| 4 | 画布 CanvasView | 节点 6 类卡片、新功能（复制粘贴/一键布局/组边/媒体提示）hover+激活态、连线选中态 |
| 5 | 知识库 KnowledgeView | 文档表格、RAG 面板、空态插画 |
| 6 | 图像生成 ImageGenView / 视频生成 VideoGenView | 表单、结果网格、上传提示 |
| 7 | 视频编辑 VideoEditView | 时间轴区域对比度 |
| 8 | 资产 AssetListView / AssetProjectView | 缩略墙、版本时间线 |
| 9 | 钱包 MyWalletView / 计费 | 金额数字排版、账单表格 |
| 10 | 项目组 ProjectGroupsView / 执行监控 | 群组卡、监控列表 |
| 11 | 反馈中心 FeedbackCenterView | 通知徽标、表单 |
| 12 | 设置 SettingsView（含全局模型供应商 tab） | Tab 切换、ProviderManageTab（C 类，重点查硬编码旧色残留） |
| 13 | 管理后台全页：用户/角色/钱包/反馈/帮助/支付渠道/定价/**审计日志** | 与其他管理页同构（PageHeader+场景+空态）；审计日志页能开（修复确认） |
| 14 | 管理后台 security 四页 | 风控仪表盘图表配色（暮山紫/石青/石绿序列） |
| 15 | 主题切换器 | 仅 2 项可选；切换 <100ms 无刷新；刷新/重开保持 |
| 16 | 存量旧主题迁移 | localStorage 手写 `theme=deep-space` → 刷新 → 夜墨生效且值被改写 |
| 17 | reduced-motion | 系统开启减少动画 → 云雾/山形动效全停 |
| 18 | 404/403/空数据 | InkEmptyState 三态插画正确 |

P1（应测，不阻断）：Safari 16.4 抽查 avif 渲染；1280px 窄窗无横向滚动；Toast 四态染色（info/success/warning/error）双主题对比度。

## 5. 本地实测环境要点（历史经验沉淀，照用）

- 起停：`agent-platform/workflow_output/docs/run-guide/{start-all,stop-all}.ps1`；日志落 `agent-platform/logs/`（UTF-16 用 Get-Content 看）。
- 后端裸 `mvn spring-boot:run` 会挂，env 在 `local-dev-env.ps1`；DB：`PGPASSWORD='aa64221886' psql -U postgres -h 127.0.0.1 -d agent_platform`。
- 账号 admin/admin123；**单会话制**（同账号再登录踢旧 token 40104），脚本先 clear。
- 浏览器 harness：Node + Playwright（`C:/Users/Administrator/AppData/Roaming/npm/node_modules/@playwright/mcp/node_modules/playwright`，chromium 已装，persistentContext 独立 profile）。**登录竞态**：页内 fetch 登录会被 SPA 残留 token 跳转打断——Node 侧直连 8080 登录拿 token，页面只写 localStorage（键 `access_token`/`refresh_token`/`user_info`，JSON.stringify 值）。
- vue-flow 自动化四坑：框选须 Full 包含；小画布 fitView zoom≠1 拖拽失准；调色板连点节点叠簇遮挡；Shift 多选包围盒盖节点（先点空白清选再拉线）。
- Windows 坑：Git Bash `/tmp` ≠ Windows `/tmp`，跨工具用 `C:/Users/Administrator/AppData/Local/Temp`；curl 中文 body 先写 UTF-8 文件 `--data-binary @file`。
- 冒烟脚本参考：`workflow_output/docs/run-guide/smoke-viii.mjs`（node 直跑模式可仿写本次走查辅助脚本）。

## 6. 缺陷分级

- **P0 阻断**：任何路由任一主题出现不可读文字（对比度不足）、白屏、控制台报错、功能回归；
- **P1 应修**：旧风格残留（硬编码色、旧圆角/阴影）、动效未随 reduced-motion 关闭、场景画未加载无兜底；
- **P2 记录**：纯审美主观项，汇总给用户裁决，不阻断交付。
