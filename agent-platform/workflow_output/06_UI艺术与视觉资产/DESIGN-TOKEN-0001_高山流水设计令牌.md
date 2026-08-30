# DESIGN-TOKEN-0001 高山流水 · 设计令牌

> 产物编号：DESIGN-TOKEN-0001 ｜ 状态：定稿（等待阶段 11 工程冻结复核）
> 架构：三层令牌（原始 → 语义 → 组件），本次重做只改原始层与语义层映射，组件层不动。
> 实现落点：`src/styles/themes/` 新增 `ye-mo.scss`（暗）+ `xuan-zhi.scss`（明）两套 `[data-theme]`，原始层变量集中在 `variables.scss`；Naive UI 经 `App.vue` 的 `themeOverrides` 接管（现状缺失，本次补齐）。

## 一、原始层（Primitive）

### 传统色板

| Token | 色名 | 色值 | 语义 |
|---|---|---|---|
| `--ink-dai` | 黛蓝 | `#151D29` | 远山墨色，暗主题主背景 |
| `--ink-yaqing` | 鸦青 | `#0F151E` | 暗主题侧栏/深层 |
| `--ink-card` | 墨锭 | `#1C2634` | 暗主题卡片面 |
| `--ink-xuanzhi` | 宣纸 | `#F5F1E6` | 亮主题主背景 |
| `--ink-xuanzi-card` | 熟宣 | `#FDFBF4` | 亮主题卡片面 |
| `--ink-mo` | 墨色 | `#26221C` | 亮主题正文 |
| `--ink-yuebai` | 月白 | `#DFE7EE` | 暗主题正文 |
| `--ink-tianqing` | 天青 | `#8FBCD4` | 暗主题品牌主色 |
| `--ink-tianqing-deep` | 深天青 | ~~#3D7A94~~ → `#35687F` | 亮主题品牌主色（WCAG 实测 4.22:1 不过线，按"调明度不动色相"压深至 5.41:1） |
| `--ink-tianshuibi` | 天水碧 | `#A8D5C8` | 选中态/图表主色 |
| `--ink-shiqing` | 石青 | `#4E7FA6` | 图表序列 1 |
| `--ink-shilv` | 石绿 | `#5E9A7E` | 图表序列 2 |
| `--ink-mushanzi` | 暮山紫 | `#8A80A3` | 图表序列 3/渐变过渡 |
| `--ink-zhusha` | 朱砂 | `#B54434` | 品牌印章/关键强调（非状态色） |
| `--ink-zheshi` | 赭石 | `#B07A45` | 暖色辅助 |
| `--ink-dian` | 靛蓝 | `#5B7BA8` | 信息色基底 |

### 状态色（独立，不可被品牌色替换）

| Token | 暗主题 | 亮主题 | 用途 |
|---|---|---|---|
| `--state-success` | `#63B98A`（竹绿） | `#3E8E63` | 成功 |
| `--state-warning` | `#D9A45B`（赭黄） | `#B07A28` | 警告 |
| `--state-error` | `#D9564A`（朱砂红·明度+15% 与品牌区分） | `#C03A2E` | 错误 |
| `--state-info` | `#7FA3CC`（靛青） | `#4A6E9E` | 信息 |

### 非色彩原始值

| Token | 值 | 说明 |
|---|---|---|
| `--font-body` | `"Noto Sans SC","PingFang SC","Microsoft YaHei",system-ui,sans-serif` | 正文 |
| `--font-display` | `"LXGW WenKai",var(--font-body)` | 标题/空状态短句（子集化 WebFont） |
| `--space-unit` | `4px`（间距基数不变） | 沿用现状 |
| `--radius-card` | `6px`（含蓄小圆角，弃大圆角） | 由 8/12px 收敛 |
| `--ease-cloud` | `cubic-bezier(0.22, 0.61, 0.36, 1)` | 云雾缓出 |
| `--dur-fast/normal/slow` | `150ms / 300ms / 400ms` | 三档时长 |
| `--texture-opacity` | `0.06`（≤0.12 红线） | 水墨/宣纸纹理透明度 |
| `--shadow-layer` | 多层 2–8% 黑，无硬阴影 | 远山叠影 |

## 二、语义层（Semantic）→ 双主题映射

| 语义令牌 | 夜墨（暗） | 宣纸（明） |
|---|---|---|
| `--color-bg-base` | `--ink-dai` | `--ink-xuanzhi` |
| `--color-bg-sider` | `--ink-yaqing` | `#ECE6D6`（宣纸·深一档） |
| `--color-bg-card` | `--ink-card` | `--ink-xuanzi-card` |
| `--color-text-primary` | `--ink-yuebai` | `--ink-mo` |
| `--color-text-secondary` | `#9AABBC` | `#6B655A` |
| `--color-primary` | `--ink-tianqing` | `--ink-tianqing-deep` |
| `--color-primary-hover` | `#A5CCE0` | `#336B82` |
| `--color-primary-pressed` | `#7AAC C4`→`#7AACC4` | `#2F5F73` |
| `--color-selected-bg` | `--ink-tianshuibi` 18% | `--ink-tianshuibi` 35% |
| `--color-accent-seal` | `--ink-zhusha` | `--ink-zhusha` |
| `--color-border` | `#2A3646` | `#D8D0BC` |
| `--color-focus-ring` | `--ink-tianqing` | `--ink-tianqing-deep` |

## 三、组件状态矩阵（门禁必查，Naive themeOverrides 全覆盖）

| 状态 | 规则 |
|---|---|
| 默认 default | 语义层直取 |
| 悬停 hover | 主色提亮一档（暗）/压深一档（明）+ 卡片绢本微光（亮度+3%）+ 2px 抬升，300ms |
| 聚焦 focus | 2px `--color-focus-ring` 焦点环 + 2px 偏移，键盘可达，禁止 `outline: none` 无替代 |
| 按下 pressed | 主色压深 + 墨滴扩散 150ms（按钮原生 ripple 改造） |
| 选中 selected | `--color-selected-bg` 天水碧底 + 左侧 3px 主色「笔锋」条 |
| 加载 loading | 骨架屏 + 扫光（`--ease-cloud` 1200ms 循环）；按钮内 spinner 用天青 |
| 成功 success | `--state-success` + Toast 图标，禁朱砂 |
| 警告 warning | `--state-warning` |
| 错误 error | `--state-error` + 共情文案 + 恢复路径（D-21.04） |
| 禁用 disabled | 透明度 45% + `not-allowed`，禁纯灰墨化 |
| 权限不足 | 锁定图标 + `--color-text-secondary` + 文案给申请路径（不复用禁用态） |

## 四、不可变边界（AI 与迭代不得覆盖）

1. 状态色语义映射；2. 对比度红线（4.5:1 / 大字 3:1）；3. `--font-body` 正文栈；4. 装饰层透明度上限 0.12；5. 动效时长上限 400ms 与 reduced-motion 降级；6. 禁止传统水墨肌理（2026-08-28 修订）。
