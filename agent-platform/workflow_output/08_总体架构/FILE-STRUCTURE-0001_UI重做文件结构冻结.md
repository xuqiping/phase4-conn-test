# FILE-STRUCTURE-0001 UI 重做文件结构冻结（视觉资产与主题层）

> 产物编号：FILE-STRUCTURE-0001 ｜ 阶段：09 总体架构（UI 重做专项：只冻结本次新增的视觉/主题文件结构，项目总体结构沿用现状）
> 上游：TECH-0001、DESIGN-TOKEN-0001、ART-ASSET-0000 ｜ 消费者：阶段 11（像素冻结）、阶段 12（资产回填）、阶段 13（实现）

## 一、实施方式（用户裁定 2026-08-28）

**隔离预览**：不直接改 `frontend/`，先完整复制为 `agent-platform/frontupdate/` 实施全部改造；用户验收效果后，再做整体替换（替换时 `frontend/` 旧目录整体归档，不逐文件合并）。下文路径均相对 `frontupdate/`（与 `frontend/` 结构一致）。

## 二、新增目录与文件（agent-platform/frontupdate）

```text
frontend/
  public/
    fonts/                              ← 新增：自托管子集化 WebFont（构建产物，由脚本生成，禁止手改）
      lxgw-wenkai-display.woff2         ← 生成物：标题/空状态用字子集
      lxgw-wenkai-display.css           ← 生成物：@font-face 声明（font-display: swap）
      OFL-LXGW-WenKai.txt               ← 许可副本（ART-QA-0001 版权基线）
  src/
    assets/
      art/                              ← 新增：美术资产落点（ART-ASSET-* 回填目录）
        login/
          splash-ink.avif / .webp       ← ART-ASSET-0001 登录泼墨大背景
        workbench/
          mist-dawn.avif / .webp        ← ART-ASSET-0002 晨版云雾层
          mist-dusk.avif / .webp        ← ART-ASSET-0002 暮版云雾层
        empty/
          empty-data.avif / .webp       ← ART-ASSET-0003 空山孤舟
          empty-forbidden.avif / .webp  ← ART-ASSET-0004 云雾封径
          empty-404.avif / .webp        ← ART-ASSET-0005 水穷云起
        brand/
          seal-logo.svg                 ← ART-ASSET-0006 印章 Logo（人工重绘 SVG）
        texture/
          brush-divider-01.svg          ← ART-ASSET-0008 飞白枯笔 ×3
          brush-divider-02.svg
          brush-divider-03.svg
    styles/
      themes/
        ye-mo.scss                      ← 新增：夜墨（暗）主题 [data-theme="ye-mo"]
        xuan-zhi.scss                   ← 新增：宣纸（明）主题 [data-theme="xuan-zhi"]
      tokens-ink.scss                   ← 新增：原始层传统色板/字体/动效原语（DESIGN-TOKEN-0001）
      naive-overrides.ts                ← 新增：Naive GlobalThemeOverrides 按主题计算
      texture.scss                      ← 新增：程序化 SVG feTurbulence 纹理工具类
    scripts/  （或 frontend/scripts/）
      subset-font.py                    ← 新增：fonttools 子集化脚本（ASSUMP-0001 验证点）
      font-glyphs.txt                   ← 新增：子集用字清单（标题/空状态/品牌文案用字）
```

## 二、既有文件改造点（不重命名、不移动）

| 文件 | 改造 |
|---|---|
| `src/stores/theme.ts` | `ThemeName` 增加 `ye-mo`/`xuan-zhi`；`THEME_LIST` 注册；保留旧 3 主题 |
| `src/App.vue` | `n-config-provider` 接入 `:theme-overrides`（按当前主题计算）+ 明暗 `:theme` 切换 |
| `src/styles/variables.scss` | 非色彩 token（圆角/阴影/时长）主题化收口，默认值引用 tokens-ink 原语 |
| `src/components/ThemeSwitcher.vue` | 新增两套主题入口与预览 |
| `index.html` | 预加载 `lxgw-wenkai-display.css`（`<link rel="preload">` 字体文件） |

## 三、约束

- **禁止修改区**：`src/api/`、`src/router/`、`src/stores/`（除 theme.ts）、全部业务逻辑组件——本次零行为变更。
- **资产生成落点（用户回填）**：仍填到 `frontend/src/assets/art/**` 或先放 `frontupdate/src/assets/art/**` 均可——两边结构一致，验收阶段统一合并。
- **生成目录**：`public/fonts/` 全部内容为 `subset-font.py` 生成物，手工修改会被覆盖。
- **资产命名**：`{用途}-{名}.{格式}` 小写连字符；avif 为主、webp 同名回退；回填时按 ART-ASSET 单验收。
- **排除项**：`.gitignore` 不动；字体源文件（未子集化的全量 TTF/OTF）**不入库**，放本地 `D:/dev-cache/` 或构建机缓存。

## 四、对 ART-ASSET-0000 的回写

资产目标目录已由此文件冻结（`src/assets/art/**` 与 `public/fonts/`），ART-ASSET-0000 状态从「等待目录与像素冻结」推进为「等待像素与性能冻结」（阶段 11）。
