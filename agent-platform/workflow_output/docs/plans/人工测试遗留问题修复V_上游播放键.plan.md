# 计划 · 上游视频 ▶ 播放标居中修复（人工测试修复V · P4 后续）

> 规格：[人工测试遗留问题修复V设计.md](../specs/人工测试遗留问题修复V设计.md) §6（2026-08-27 用户反馈，单 chunk 微修）。
> 前置状态：▶ span + 样式已在工作区未提交改动中（上一会话 P4 后续补丁），本计划只修 SCSS 嵌套错位。

## Chunk PB-1 · SCSS 嵌套错位修复（1 文件）

- **目标**：上游面板视频卡 ▶ 播放标居中覆盖首帧（同参考区 `ref-preview__play` 视觉）。
- **动作**（伪代码，只动 `<style>` 块，模板/测试零改动）：
  1. `PropertyPanel.vue` style：剪出 `&__up-thumb { ... }` 内的 `&__up-play { ... }` 块
  2. 粘贴到 `.prop-panel` 直接层级（与 `&__up-card` / `__up-meta` 平级）
     → 编译产物从 `.prop-panel__up-thumb__up-play`（永不命中）变为 `.prop-panel__up-play`（命中模板 :59）
  3. 块内属性保持：`position:absolute; inset:0; display:flex; 居中; color:#fff; text-shadow; pointer-events:none; z-index:3`
  4. `font-size: 13px → 16px`（对齐参考区 ReferencePreview.vue:99-109）
- **涉及文件**：`frontend/src/components/canvas/PropertyPanel.vue`（1 个）
- **依赖**：无
- **验证**：
  - `npx vitest run src/components/canvas/PropertyPanel.test.ts` —— 既有 span 存在断言（:337）+ 上游/占位/is-clickable 全绿
  - `npx vue-tsc --noEmit`
  - **人工视觉**（vitest 验不了 CSS 编译产物）：上游视频卡 ▶ 居中盖首帧；hover 1.6x 放大时 ▶ 锚定 44×44 缩略框中心；点击开 Lightbox；参考区/图片卡不回归

## 技术坑点

- **SCSS `&` 拼接**：`&__x` 嵌在 `&__y` 块内 = 选择器 `.…__y__x`（双元素类）——BEM 新元素类必须与兄弟元素**平级**。本 bug 即此坑，修复=降一层。
- vitest 只断言 DOM 存在性，CSS 选择器失配测试不可见——人工视觉验收必须留。

## 联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 上游视频卡渲染首帧 | ▶ 播放标 | 居中覆盖（z3） | 图片卡/占位卡**不出现** ▶（v-else-if 分支锁） |
| hover 缩略图 1.6x 放大 | ▶ 位置 | ▶ 不随放大，仍在缩略框中心（z3 > video z2） | 放大溢出卡外（overflow:visible）不裁切 ▶ |
| 点击 ▶ 区域 | Lightbox | ▶ pointer-events:none，点击落到按钮开 Lightbox | 双击卡片插 @引用手势不回归（click 不拦 dblclick） |

## 运维考量（7 类逐条）

- 可观测性：**不做**（纯 UI 展示，无日志需求）
- 配置开关：**不做**（无行为分支）
- 可回滚：**做**（单文件样式块移动，git revert 即回）
- 限流/熔断：**不做**（无后端交互）
- 运维入口：**不做**（不适用）
- 告警阈值：**不做**（不适用）
- 容量/性能：**不做**（零新增渲染成本，静态定位）
