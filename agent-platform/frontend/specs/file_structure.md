# file_structure.md — frontend 目录结构指南（「高山流水」移植后）

> 版本 v1.0 · 2026-08-30。配套：[PRD.md](PRD.md)、[architecture.md](architecture.md)、[testing_strategy.md](testing_strategy.md)。
> 目的：告诉后续开发者（人与 AI）每个目录职责、新设计系统的文件落点、新增页面如何接入新风格。

## 1. 顶层布局

```
agent-platform/frontend/
├── specs/                    ← 本规格套件（SDD 唯一真相源）
├── public/
│   ├── favicon.svg
│   └── fonts/                ← 【新增·搬入】霞鹜文楷子集 45KB + OFL 许可 + css
│                                静态服务不经构建；改诗签/文案新增字形后重跑 subset-font.py
├── scripts/                  ← 【新增·搬入】
│   ├── subset-font.py        字体子集化（输入 font-glyphs.txt）
│   ├── check-contrast.mjs    WCAG 对比度校验（npm run check:contrast）
│   └── font-glyphs.txt       字形清单真值源
├── src/
│   ├── api/                  HTTP 封装与各业务 API（不涉样式，移植不动）
│   ├── assets/
│   │   └── art/              ← 【新增·搬入】50MB 美术资产
│   │       ├── brand/        品牌印章（seal-logo，LoginView 引用 webp）
│   │       ├── empty/        空态三态：404/无数据/无权限（InkEmptyState 引用）
│   │       ├── login/        登录泼墨（AuthLayout 引用）
│   │       ├── scenes/       24 幅模块场景（12 模块×夜墨/宣纸，仅 avif 被 glob 引用）
│   │       └── workbench/    晨昏云雾（u-mist-layer 用）
│   ├── components/           通用组件
│   │   ├── ModuleScene.vue   ← 【新增·搬入】山景横幅（读 config/scenes.ts）
│   │   ├── PageHeader.vue    ← 【新增·搬入】页头标准件（标题+场景+诗签）
│   │   ├── InkEmptyState.vue ← 【新增·搬入】空态标准件（插画+短语）
│   │   ├── AppHeader.vue     顶栏（含 ThemeSwitcher 挂载）
│   │   ├── Sidebar.vue       侧栏（含审计日志入口权限控制）
│   │   └── ThemeSwitcher.vue 主题选择器（读 store.visibleThemes，隐藏主题不可见）
│   ├── components/{chat,canvas,knowledge,...}/   业务域组件（逻辑不动，样式走令牌）
│   ├── config/
│   │   ├── modules.ts        既有模块注册（不变）
│   │   └── scenes.ts         ← 【新增·搬入】12 模块场景注册表（真值源，禁现场发挥诗签）
│   ├── layouts/              MainLayout（主框架）/ AuthLayout（登录框架，泼墨背景）
│   ├── router/               路由（不变；AuditLogView 路由本就存在）
│   ├── stores/               Pinia：theme.ts（5 主题定义+hidden 标志+迁移逻辑）
│   ├── styles/
│   │   ├── tokens-ink.scss   ← 【新增·搬入】原语层（只被主题层引用）
│   │   ├── naive-overrides.ts← 【新增·搬入】Naive UI 主题接管层
│   │   ├── texture.scss      ← 【新增·搬入】意境工具类（装饰 ≤0.12 红线）
│   │   ├── themes/           5 套主题：ye-mo/xuan-zhi（可见）+ 旧 3 套（隐藏保留）
│   │   ├── variables.scss    通用 Sass 变量
│   │   └── global.scss       全局样式
│   ├── views/                页面组件（含 admin/ 管理后台、admin/logs/AuditLogView 审计日志）
│   └── main.ts               入口（样式导入顺序：原语→变量→主题→纹理→全局）
├── index.html                （+1 行字体 preload）
└── package.json              （+1 行 check:contrast script；依赖零新增）
```

【新增·搬入】= 从 frontupdate 原样复制，内容见 architecture §4-A。

## 2. 新增页面/组件接入新风格的标准姿势

1. **页头**：用 `PageHeader`（传 `scene-key`），自动获得该模块山景+诗签；诗签文案只准从 `config/scenes.ts` 取，不现场写；
2. **空态**：用 `InkEmptyState`（404 / 无数据 / 无权限三态），不手绘空态；
3. **颜色**：只用语义 CSS 变量（`--bg-body`/`--text-1` 等，定义在 themes/*.scss），**禁止硬编码十六进制色值**——写死会破坏双主题之一；
4. **字体**：正文用默认栈；标题/空态短句加 `u-display-font` 类（每屏 ≤1 处）；
5. **新模块**：在 `config/scenes.ts` 注册场景（rgb+诗签+山形种子），再加 `assets/art/scenes/` 双主题 avif 两幅（命名 `scene-<key>.avif` / `scene-<key>-light.avif`，后者可缺省回退 CSS 山形渐变）；
6. **文案新增字形**：跑 `python scripts/subset-font.py` 重新子集化字体，否则展示字体静默回退正文栈。

## 3. 主题系统改动守则

- 改色值：动 `tokens-ink.scss`（原语）或 `themes/*.scss`（语义映射），**同步更新** `naive-overrides.ts` 与 `check-contrast.mjs` 色对清单，跑对比度校验；
- 恢复旧主题对外可见：`stores/theme.ts` 删对应 `hidden: true`；
- 主题切换零整页刷新是底线（CSS 变量 + ConfigProvider 响应式），禁止引入需要 reload 的主题逻辑。

## 4. frontupdate / frontnew 目录角色（过渡期说明）

- `agent-platform/frontupdate/` — 设计素材源，移植完成后**冻结不再同步**（历史参考 + 美术源档）；
- `agent-platform/frontnew/` — 更早的独立原型（mock 数据），与本项目无代码血缘，不引用；
- 后续一切前端开发只在 `agent-platform/frontend/`。
