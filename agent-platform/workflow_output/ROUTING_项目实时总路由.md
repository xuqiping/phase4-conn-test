# ROUTING · 项目实时总路由（agent-platform）

> 唯一一级总入口（工作流规范：00_全局治理/12_完整产出目录结构与落盘规则.md）。
> 本文件只存**当前状态指针**，不存内容；超过 5000 tokens 时必须拆分子路由到 `_ROUTING/`。
> 最近更新：2026-08-28（中国风 UI 重做专项）

## 当前焦点：中国风 UI 重做（高山流水 · 现代化缥缈空灵）

| 项 | 当前值 |
|---|---|
| 批准方向 | ART-DIR-0001「高山流水」B+吸收C（2026-08-28 批准；同日修订：去水墨、转现代化渐变/雾感/极简山形） |
| 实施位置 | `agent-platform/frontupdate/`（隔离预览，验收后整体替换 `frontend/`） |
| 默认主题 | `ye-mo` 夜墨（已改）；`xuan-zhi` 宣纸为亮色配套 |
| 构建状态 | ✅ 编译通过；WCAG 20 组色对全过（scripts/check-contrast.mjs） |
| 实机验证 | ✅ Playwright 截图核验（登录页双主题+字体+粒子关闭+持久化） |

## 产物索引（本专项）

| 阶段 | 产物 | 状态 |
|---|---|---|
| 07 UI艺术 | [06_UI艺术与视觉资产/](06_UI艺术与视觉资产/README.md)：AESTH-0001 / ART-DIR-0000·0001 / STYLE-DNA-0001 / DESIGN-TOKEN-0001 / ART-QA-0001 | 定稿 |
| 07 全模块设计 | ART-DIR-0002 全模块页面级设计（2026-08-29：公共层 P1-P7 + 12 模块签名 + AC-07~12），依据 Playwright 24 路由双主题实拍 | 已实施；模块场景部分被 0002R 取代 |
| 07 模块场景重做 | AESTH-0002（意境密度 L0-L4 刻度，首版仅 L1 失败归因）→ ART-DIR-0002R 三竞案（用户裁定方向二「雾中浮岛」）→ STYLE-DNA-0002 / DESIGN-TOKEN-0002 / ART-QA-0002 | 已实施 |
| 07 资产 | ART-ASSET-0003 最终提示词清单（现代化版） | ✅ 用户供图已回填：PNG→AVIF/WebP 转码全达标（splash 60KB/35KB 等），7 资产接入代码，Playwright 截图验收通过（ART-QA-0001 双主题实机） |
| 07 场景图 | ART-ASSET-0009 模块场景图提示词包（雾中浮岛 12 景） | ✅ 双主题 24 幅全在线：夜墨 12 幅用户生成（0.35 压淡+融边纱罩）；宣纸 12 幅用户按附录 B v2 提示词 AI 生成回填（08-30 验收：三铁律全过，AVIF 8.5-20KB/张，实拍六页通过；派生 v7 转兜底，#5 峰顶微点记观察项） |
| 08 技术选型 | [07_技术选型/TECH-CAND-0001](07_技术选型/TECH-CAND-0001_UI重做技术选型.md)：字体子集化实测 44KB（ASSUMP-0001 关闭） | 定稿 |
| 09 目录冻结 | [08_总体架构/FILE-STRUCTURE-0001](08_总体架构/FILE-STRUCTURE-0001_UI重做文件结构冻结.md)：frontupdate 隔离实施 | 定稿 |
| 11 规格 | [10_详细规格与契约/UI-0001](10_详细规格与契约/UI-0001_视觉契约与资产像素冻结.md)：AC×6 + 像素冻结 + 性能预算 | 定稿 |

## 已实现（frontupdate/，构建绿）

- 令牌原始层 `styles/tokens-ink.scss`；双主题 `themes/ye-mo.scss` / `xuan-zhi.scss`
- Naive themeOverrides 接管层 `styles/naive-overrides.ts`（补现状缺失层）；`App.vue` 动态基座+overrides
- 主题注册与默认 ye-mo（`stores/theme.ts`）；登录页意境层（远山渐变/云雾缓漂/诗句）；主内容区晨昏云雾层（CSS 占位，图片钩子 `--mist-img`）
- 意境空状态组件 `components/InkEmptyState.vue`（data/forbidden/lost 三场景，CSS 山形占位）
- 字体管线 `scripts/subset-font.py`（霞鹜文楷子集 44KB + OFL 许可入库）；对比度校验 `scripts/check-contrast.mjs`
- Playwright 实测脚本：`D:/dev-cache/pwtest/verify2.js` / `verify3.js`（资产接入后双主题截图验收）
- 美术资产已接入：`src/assets/art/`（login 大图 / workbench 晨昏云雾×2 / empty 三场景×3 / brand 知音印章透明版），AVIF+WebP 双格式，全部在像素/体积预算内
- **2026-08-29 全模块重构（ART-DIR-0002）**：侧栏印章+朱砂竖痕 / AppHeader 文楷标题+发丝线 / PageHeader 组件 / u-ink-card 绢本卡 / 表格深化 / 云雾层 0.18 / 聊天空态收口 / 24 处页面级空态 / 13 admin 页统一页头 / 资产卡裸文本 bug 修复（render 函数+scoped 坑）；构建绿 + 对比度 30 组全过 + 17 单测过 + audit2 复检截图通过 + 旧三主题回归零变化
- **2026-08-29 雾中浮岛场景（ART-DIR-0002R 方向二）**：`config/scenes.ts` 12 模块专属传统色+诗签+山形种子真值表 + `components/ModuleScene.vue` 场景引擎（天际渐变/椭圆远山/右缘竖排诗签，z-index 沉底，仅 ink 双主题 v-if 渲染）；25 页全量接入（11 主模块专属景 + admin 13 页淡版×0.5）；audit3 实机复检 32 路由全 OK + 旧三主题 18 路由零泄漏；复检调整：admin 淡版自动关诗签（通栏表格截断残句）、宣纸山形改墨色、诗签起点避开页头（记 ART-QA-0002）；顺带修复 5 个视图测试补 pinia + theme.test 两条过期断言；**08-29 晚二次复检**：题跋 gutter 规则（ink 主题宽屏非 lite 页右侧预留 56px，诗签不再被表格遮挡）+ 宣纸景窗图派生定稿 v7（用户定调「几乎都展示、黑影用透明度调」：p40 保留更多+缓坡 15→42%+沉底锚定 100% 页底+强度 0.65，浓淡旋钮=脚本 STRENGTH×CSS opacity，参数记 ART-QA-0002#5，脚本 derive_light.py 固化支持 DL_* 单张微调），phase4 实拍六页全过
- 后端环境重建（C 盘清理后）：JDK17/Maven3.9.16/PG16.10+pgvector/Redis5 全在 D 盘，`start-backend.ps1` 一键起，159 迁移 114 表

## 后续任务（按序）

1. ☑ 用户生成图片资产 → 回填 → 验收（2026-08-28 完成：转码接入 + verify3 双主题截图验收通过；印章 WebP 为过渡版，后续 SVG 重绘）
2. ☑ 各页面 `n-empty` 批量替换为 InkEmptyState（2026-08-28 完成：19 文件 24 处页面级空态已换；弹窗/抽屉/气泡内 size=small 保留原样；build 绿 + AgentDetailView 测试 5/5；mock 后端截图目检 assets/image-gen/canvas 双主题通过。注：agentHall/execution 模块本项目关闭，forbidden/lost 两型仅深层兜底无可达路由）
3. ◐ POC-0001 组件七态逐页目检（Button/Input/Table/Dialog/Menu 冒烟过编译，待真机目检）；☑ 宣纸错误 Toast 对比度已修（Message 四态染色底+高对比正文，check-contrast 增至 30 组全过）
4. ☑ 性能复测（2026-08-28 preview 生产构建：FCP≈310ms ≤1.8s ✓、LCP≈650ms ✓、CLS=0.0000 <0.02 ✓、字体 45.2KB ≤500KB ✓；本地 preview 网络近零延迟，线上需按真实带宽复核）
5. ☐ 用户验收 frontupdate 效果 → 整体替换 frontend/（旧目录归档，不逐文件合并）→ 灰度发布（默认主题仍旧→观察 48h→新用户默认 ye-mo，UI-0001 第四节）
6. ☐ GATE-07/08/09/11 正式签发登记（治理流程）；总路由随每次产物变更同步（本文件）

## 项目其他工作线（非本专项，仅登记指针）

- 主开发线进度：`workflow_output/开发进度/`（旧工作流结构，独立维护）
- 前端工程基线：`frontend/`（生产现行版）；预览版：`frontupdate/`
