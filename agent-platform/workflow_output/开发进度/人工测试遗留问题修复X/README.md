# 修复X · 功能 README（视频节点上传 + 从库选择全类型预览 + 组边随 ⛓ 保留）

> 2x 未解决三项的收口轮。规格 `docs/specs/人工测试遗留问题修复X设计.md` · 计划 `docs/plans/人工测试遗留问题修复X.plan.md` · 人工用例 `docs/测试方案/人工测试遗留问题修复X测试方案.md`（R1-R15）

## 用户地图

| 谁 | 场景 | 效益 |
|---|---|---|
| 创作者（有 canvas:write） | 手里已有现成视频素材，想直接进画布参与生产 | 不必绕道生成——上传即节点产物，可 @引用/抽帧/截取/入库 |
| 创作者 | 「从库选择」时不知道资产长什么样 | 四类全预览（图悬浮放大+灯箱/视首帧+播放/音行内试听/文正文片段），看清再选、点空白不误选 |
| 创作者 | 复制与组相连的节点后连线丢失，要手动重连 | ⛓ 开=副本自动接回原组（两向），组广播/聚合照常算数 |

## 技术说明（简）

- **X-1 视频上传**（`97d6547f`）：PropertyPanel 视频区顶部 n-upload，复用图片节点 `upload` 事件链零后端改动；预检走 `utils/mediaLimits.ts` 单源（视 50/图 30/音 15MB），MIME 判空跳过交后端闸。
- **X-2 从库预览**（`9c0cf4c6`+`5c7c6625`）：新 `AssetPickerRow.vue` 行组件（useLazyFilePreview 每行一实例，IntersectionObserver 惰性拉 blob URL）；图/视缩略接 HoverPreviewImage（kind 扩 video），点击开 Lightbox（z-index 3000 盖 modal）；音=行内播放条、文=textPreview 片段+字标回落；「选择」按钮唯一选中入口。
- **X-3 组边保留**（`85d9ffd1`+`45723976`）：canvasClipboard 跨集边去组端点过滤（节点↔组恰一端在集被收），remapCrossEdges 增 aliveGroupIds（组伪 id 保原+解散丢边）；CanvasBoard 复制混合入参/粘贴产物分流（组边进 groupEdges 池绝不进 v-model）/appendEdges 批次分流剥会话 class；CanvasView 副本链改传两池合并。
- **测试**：vitest 全量 1014/1014（+40 用例）、vue-tsc 0 错；零 DB 迁移、零新依赖、零新端点。

## 进度文档

- 开发进度1.md — A 轮（视频上传）
- 开发进度2.md — B 轮（从库预览）
- 开发进度3.md — C 轮（组边保留）
- 开发进度4.md — D 轮（文档收尾）

## 待人工验证

R1-R15 全过 → 问题单 `人工测试问题/2x. 资产库和无限画布.md` 三项销项（现挂「待人工验证（修复X）」）。
