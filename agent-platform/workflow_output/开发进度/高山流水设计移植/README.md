# 高山流水设计移植 · README

> 功能受众 **C 类**（用户可见 + 技术实现）。2026-08-30 · 分支 feat/gsl-port（11 commit，C0~C10）。

## 用户地图

**谁用**：平台全部用户。**场景**：换明暗皮肤、获得统一水墨视觉。**效益**：夜墨护眼、宣纸明快；对比度全部达 WCAG（正文 ≥4.5:1）。

- 换肤：顶栏调色盘弹窗（夜墨/宣纸）或登录页色卡；即时生效、永久记住
- 老用户：旧皮肤存量值自动迁夜墨，无幽灵态
- 视觉：12 模块山水场景+诗签、统一页头、水墨空态（404/无数据/无权限）、霞鹜文楷标题字

操作细节 → [用户操作手册](../../docs/user-ops/高山流水设计移植用户操作手册.md)

## 技术说明

**做了什么**：把 `frontupdate/` 高山流水设计系统移植进 `frontend/`，保留 frontend 全部业务逻辑（19 commit）。

**四层架构**（详见 [Feature Map](../../docs/feature-map/高山流水设计移植.feature-map.md)）：

1. 原语层 `tokens-ink.scss`（16 传统色变量）
2. 语义层 `themes/{ye-mo,xuan-zhi}.scss`（`--color-*` 语义变量，组件只引语义名）
3. 组件库接管 `naive-overrides.ts`（夜墨→darkTheme 基座、宣纸→null 亮基座）
4. 组合层 ModuleScene/PageHeader/InkEmptyState + `scenes.ts` 注册表（12 模块，avif 懒加载）

**移植方法**（五分类，规格见 [specs/architecture.md](../../../frontend/specs/architecture.md) §4）：

- A 类 84 文件机械搬入（资产/字体/令牌/新组件）
- B 类 45 文件纯样式取 frontupdate
- C 类 22 文件零触碰（从未换设计，语义变量自动换肤）
- D 类 3 文件三向合并（`git merge-file`，frontupdate 样式 × frontend 逻辑，零冲突零丢行）
- E 类按新风格重设计（AuditLogView 全套；LoginView 补订阅 visibleThemes）

**主题治理**：5 主题全保留，旧 3 套 `hidden: true` 只藏不删（恢复=删标志一行，已演练）；`initTheme` 迁移存量值。

**质量门**（全过）：vitest 971/971 · vue-tsc 0 错 · check:contrast 全色对达标 · build 20.3s · dist 4.4M→5.0M（+0.6M < 2MB 目标）· 字体 45KB · 场景 avif 8-29KB · 27 路由 × 2 主题浏览器走查 0 报错 0 坏图 · 切换 0ms · reduced-motion 动效全停。

**维护要点**：
- 新页面接入按 [specs/file_structure.md](../../../frontend/specs/file_structure.md) 六步姿势（场景注册→PageHeader→InkEmptyState→色对→字体字形→双主题走查）
- 改色值必跑 `npm run check:contrast`；新增文案字跑 `scripts/subset-font.py`
- 走查清单在 [specs/testing_strategy.md](../../../frontend/specs/testing_strategy.md) §4（18 项）

**遗留人工项**（⚑ 不阻断）：双主题主观观感终审、Safari avif 抽查、画布新功能双主题手工操作细测（vue-flow 自动化四坑见 testing_strategy §5）。
