# 设计系统规格 — 4 主题 + 画布节点卡片

> 依据联网调研（来源见 §8）。本文件是视觉的唯一真相源，tokens 落盘为 `src/styles/tokens/` 下 CSS 变量文件。

## 1. 趋势依据（调研结论摘要）

1. **暗色优先**已成 AI/开发者工具默认（Linear、Vercel、Supabase、Sentry）：用「更浅的表面色」做层级而非重阴影；典型表面栈 #0F0F0F → #1A1A1A → #1F1F1F → #2A2A2A。
2. **玻璃拟态用于分层**（侧边栏、浮层、监控面板），不全屏滥用（损可读性）。
3. **AI-native**：AI 输出是一等公民组件（摘要卡、状态徽标），不是悬浮聊天窗。
4. **节点编辑器通行模式**：卡片=彩色头部（按类型色编码）+内容区+底部；连接桩左入右出；贝塞尔曲线连线；节点六态（默认/hover/选中/运行/完成/报错）。
5. **克制的强调色**：每主题 1 个主强调色 + 语义色（成功/警告/报错），拒绝彩虹。

## 2. 共享基础（4 主题通用）

### 2.1 表面层级（surface stack）

统一 5 级，变量名固定，值按主题变：

| token | 用途 |
|-------|------|
| `--sf-0` | 页面底色 |
| `--sf-1` | 侧边栏/顶栏 |
| `--sf-2` | 卡片 |
| `--sf-3` | 弹层/悬浮面板 |
| `--sf-4` | 模态框 |

### 2.2 语义色

`--accent`（主强调）、`--ok` 成功、`--warn` 警告、`--err` 报错、`--info` 信息。文字三级：`--tx-1` 主文字 / `--tx-2` 次要 / `--tx-3` 弱化。

### 2.3 形状与动效

- 圆角：`--r-sm: 6px`（徽标/按钮）、`--r-md: 10px`（输入框）、`--r-lg: 14px`（卡片）、`--r-xl: 20px`（节点卡片/大面板）；
- 动效时长：`--d-fast: 120ms`（hover）、`--d-mid: 200ms`（面板）、`--d-slow: 320ms`（主题切换渐变）；
- 缓动：`cubic-bezier(0.22, 1, 0.36, 1)`（**easeOutExpo 近似**——先快后慢，收尾柔和）；
- 间距基数 4px，组件内边距常用 12/16/20px。

### 2.4 字体

- UI：`Inter, "PingFang SC", "Microsoft YaHei", sans-serif`；
- 数字/代码：`"JetBrains Mono", Consolas, monospace`（耗时、token 数等用等宽，观感更「工程」）；
- 字号阶：12 / 13 / 14（正文）/ 16 / 20 / 24 / 32。

## 3. T1 霓虹 AI「Neon Pulse」

**气质**：深空控制台，AI 在发光。

- 底色：`--sf-0: #070B14`（深蓝黑）；卡片 `rgba(148,163,255,0.06)` + `backdrop-filter: blur(14px)`（玻璃拟态）；
- 主强调：`--accent: #7C5CFF`（电紫）；辅助光色 `#22D3EE`（青）；
- 辉光：选中元素 `box-shadow: 0 0 0 1px var(--accent), 0 0 24px color-mix(in srgb, var(--accent) 35%, transparent)`；
- 流光：运行中节点描边用 `conic-gradient` 旋转动画（2.4s/圈）；
- 背景：极淡的径向渐变光斑（紫/青各一，透明度 ≤6%）+ 可选噪点纹理；
- 风险管控：辉光只出现在选中/运行/hover，默认态收敛，避免「全屏霓虹」廉价感。

## 4. T2 冷静极简「Calm Slate」

**气质**：Linear 式专业工具，零装饰。

- 表面栈：`#0F0F10 / #161617 / #1C1C1E / #242426 / #2E2E31`（中性灰，不带色相）；
- 主强调：`--accent: #5E6AD2`（Linear 紫蓝），仅用于主按钮、激活导航、选中态；
- 无辉光、无渐变、无玻璃；层级靠 1px `rgba(255,255,255,0.06)` 描边 + 表面色差；
- 节点卡片选中态 = 描边加粗到 2px + 左上角类型色条；运行态 = 状态点呼吸（仅 opacity 动画）；
- 排版为王：行高 1.55，段落间距加大，信息密度低一档。

## 5. T4 影像工坊「Cineon」

**气质**：AI 视频生成工作站——放映厅的暖黑 + 胶片语言。

- 底色：`--sf-0: #12100E`（暖黑，影院幕布氛围）；表面带极轻微暖调；
- 双强调：`--accent: #F59E0B`（胶片橙金，主操作）+ `--accent-2: #EC4899`（品红，AI/生成相关）；
- 设计母题：
  - **时间轴刻度**：画布小地图、卡片底部状态条借鉴剪辑软件时间轴样式（细刻度线+播放头）；
  - **分镜框**：图像/视频/分镜节点缩略图带 1.85:1 或 16:9 遮幅比例与「取景框」四角标记（**遮幅**——电影画面的宽高比黑边）；
  - **场记板编号**：节点标题右侧显示 `#SC-01` 式序号徽标；
- 运行中：节点播放头扫过底部时间轴条（translateX 动画）；
- 字体：标题可用 `"Space Grotesk", "PingFang SC"`，带轻微字距，海报感；
-  Glas 程度介于 T1/T2 之间：卡片半透明但不模糊（省性能）。

## 6. T3 混合「Hybrid Glow」

= T2 的表面栈与排版 + 画布区域内节点卡片沿用 T1 的玻璃+辉光。页面其余部分零辉光。**存在意义**：验证「克制的页面 + 出彩的画布」是否是最优日常组合。

## 7. 画布节点卡片解剖（6 类型 × 6 状态）

### 7.1 结构

```
┌──────────────────────────────┐
│ ●图标  类型名      #SC-01 ⌄ │ ← 头部：类型色条（左 3px）或类型色图标
│ ──────────────────────────── │
│        内容预览区             │ ← 类型特有（见 7.2）
│ ──────────────────────────── │
│ ▶ 状态条 / 耗时 / token 徽标  │ ← 底部：状态+元信息
└──────────────────────────────┘
 ○（上入桩）            （下出桩）○
```

> 连接桩方向对齐现有 frontend（上入下出，垂直流水线：脚本→分镜→图像→视频），不采用调研中的左入右出。（2026-08-14 修订）

### 7.2 类型规格

| 类型 | 色（T1/T2/T3） | 色（T4） | 内容预览 |
|------|----------------|----------|----------|
| 文本 Text | 蓝 #3B82F6 | 青蓝 #38BDF8 | 摘要 2 行截断 |
| 图像 Image | 紫 #A855F7 | 橙金 #F59E0B | 16:9 缩略图占位 |
| 视频 Video | 品红 #EC4899 | 品红 #EC4899 | 1.85:1 缩略图+时长徽标 |
| 音频 Audio | 青 #06B6D4 | 暖绿 #84CC16 | 波形占位（div 条形组） |
| 脚本 Script | 琥珀 #F59E0B | 暖白 #E7E5E4 | 行数徽标 + 首行代码 |
| 分镜 Storyboard | 绿 #22C55E | 橙红 #F97316 | 镜头数徽标 + 2×2 小图阵 |

### 7.3 状态视觉矩阵

> 状态分两层：**数据状态**（`idle/running/success/failed`，枚举对齐现有 frontend `CanvasNodeStatus`，由 mock 数据驱动）+ **交互状态**（`hover/选中`，由用户操作触发）。组件 `node.data.status` 只管前者。（2026-08-14 修订：原 done/error 改为 success/failed）

| 状态 | T1/T3 | T2 | T4 |
|------|-------|----|----|
| 默认 | 玻璃卡+细描边 | 平面卡+细描边 | 暖黑半透明卡 |
| hover | 描边变类型色+微浮起(translateY -1px) | 描边变亮 | 描边变类型色 |
| 选中 | 类型色辉光 | 2px 类型色描边 | 类型色描边+取景框角标亮起 |
| 运行中 | conic 流光描边 | 状态点呼吸 | 底部播放头扫描 |
| 完成 | ✓角标（--ok） | ✓角标 | ✓+时间轴条满格 |
| 报错 | 红描边+红徽标 | 红描边 | 红描边+时间轴条红段 |

### 7.4 连线与小地图

- 连线：贝塞尔曲线，默认 `rgba(148,163,184,0.35)`，hover/选中时取源节点类型色；选中连线中点显示删除钮（沿用现有 DeletableEdge 交互）；
- 小地图：节点色点按类型着色，T4 主题下改为时间轴刻度外观。

## 8. 调研来源

- [35 SaaS Dashboard Design Examples, Trends and Patterns (2026) — 925Studios](https://www.925studios.co/blog/saas-dashboard-design-examples-2026)
- [7 SaaS UI Design Trends for 2026 — SaaSUI.Design](https://www.saasui.design/blog/7-saas-ui-design-trends-2026)
- [SaaS Dark Mode UI Design Best Practices — Orbix Studio](https://www.orbix.studio/blogs/saas-dark-mode-ui-design)
- [10 Best SaaS Dashboard Design Examples & Trends (2026) — AdminLTE](https://adminlte.io/blog/saas-dashboard-design-examples/)
- [12 SaaS Design Trends for 2026 — Design Studio UI/UX](https://www.designstudiouiux.com/blog/top-saas-design-trends/)
- [50 Best Dashboard Design Examples for 2026 — Muz.li](https://muz.li/blog/best-dashboard-design-examples-inspirations-for-2026/)
- [AI SDK Elements — Node 组件（卡片式节点解剖）](https://elements.ai-sdk.dev/components/node)
- [v0 — React Chat Flow Editor 模板](https://v0.app/templates/react-chat-flow-editor-goebgRrV043)
- [Higgsfield AI Canvas — 节点式图像/视频工作流](https://higgsfield.ai/canvas-intro)

## 9. 术语表

| 术语 | 大白话 | 案例 |
|------|--------|------|
| conic-gradient | 圆锥渐变：绕圆心变色的渐变，可做旋转流光 | 运行中节点的彩色描边 |
| backdrop-filter | 背景滤镜：让元素背后的内容模糊 | 毛玻璃侧边栏 |
| color-mix() | CSS 颜色混合函数 | 把强调色兑 35% 透明做辉光 |
| 遮幅 | 电影画面固定宽高比形成的上下黑边 | 1.85:1 视频缩略图 |
| 贝塞尔曲线 | 平滑曲线，节点连线的标准画法 | 从出桩到入桩的 S 形线 |
| easeOutExpo | 先快后慢的动画节奏 | 面板弹出收尾柔和 |
