# 修复VII · 节点复制粘贴 + 一键整理布局（README）

> 来源：2x 问题单最后 2 项未解决。规格 | 计划 | 进度：同目录及 `docs/specs`、`docs/plans`、`开发进度1/2.md`。

## 用户地图（B 类功能）

**谁用**：无限画布创作者——反复试提示词组合的人、搭多步生成链（图→图→视频）的人。

**场景与效益**：

| 场景 | 以前 | 现在 |
|---|---|---|
| 「这个图片节点配置不错，再来一个类似的改改」 | 只能「创建副本」一个个建，位置固定在旁边 | 框选若干节点 Ctrl+C，鼠标移到哪 Ctrl+V 粘到哪，成组带内部连线 |
| 「链 A→B→C 整段想再试一组参数」 | 手动逐个建+逐条连线 | 框选整段一次复制，粘贴即得同构子图，连线/相对位置原样 |
| 「画布越用越乱，线交叉看不清流向」 | 手动一个一个拖 | 点工具条 ✨ 一键整理：上游在左下游在右，自动缩放看全图，不满意 Ctrl+Z 一步回 |

**关键语义（用户须知）**：
- 粘贴体与原节点**彻底脱钩**（同「创建副本完全独立」口径）：对副本重生成/编辑/入库不影响原件。
- 复制只带**选中集内部**的连线；连向集外的边不带。
- 粘贴体名字撞名自动加序号（「图片生成」「图片生成 2」…）；正文里 @引用不重写（仍指原节点）。
- 连按 Ctrl+V 逐次错开 32px 不重叠；Ctrl+Z 一步撤回整组。
- 复制过节点后想粘外部图片：Esc 清选再 Ctrl+C 一次即恢复图片通道。
- ✨ 整理：无选中=排全图，有选中=只排选中（组整组跟排）；整理只挪位置不改连线/内容。

## 简要技术说明

| 模块 | 位置 | 一句话 |
|---|---|---|
| dagre 布局核心 | `frontend/src/utils/autoLayout.ts` | LR 分层重排：中心点→左上角换算、新旧 bbox 左上锚定（全图/子图同口径）、16 网格对齐、自环不参与分层 |
| 复制粘贴纯函数 | `frontend/src/components/canvas/canvasClipboard.ts` | buildCopySet（诱导边）/planPastePositions（+32 错开）/planLabels（三级去重）/remapEdges（自环重映） |
| 脱钩口径 | `frontend/src/components/canvas/nodeClone.ts` | `RESET_KEYS` 共享导出，与「创建副本」同源 |
| UI 接线 | `frontend/src/components/canvas/CanvasBoard.vue` | ✨ 按钮+onAutoLayout；Ctrl+C/V 键控路由（内部剪贴板 > 外部图片 > 浏览器默认）；pasteSubgraph 单历史步直批 |
| toast | `frontend/src/views/CanvasView.vue` | @nodes-copied → 「已复制 N 个节点」 |

**三个核心决策**（详见 spec Q1-Q4）：
1. **单撤回步**：粘贴/整理各只一次 pushHistory + 一次 structure-changed——批量改动绝不走逐节点 API（会拆多步）。
2. **粘贴优先级链**：keydown preventDefault 会吞后续 paste 事件——内部剪贴板非空才拦截；无选中 Ctrl+C 不 preventDefault，外部图片通道自动恢复。
3. **落点锚定**：粘贴=包围盒中心对鼠标；整理=新包围盒左上角贴旧包围盒左上角（视线不跳，优于归一 0,0）。

## 验证

- 自动化：vitest 901/901 绿（autoLayout 10 + canvasClipboard 9 + CanvasBoard 新增 12），vue-tsc 0 错。
- 人工：`docs/测试方案/人工测试遗留问题修复VII测试方案.md` J1-J8 + K1-K10（真手势项：鼠标落点/视口缩放/组包围盒跟随）。

## commit 链

`2b7b7ed0`（dagre+autoLayout）→ `404b77ab`+`f451eb9d`（✨ 接入）→ `6b639e0e`（clipboard 纯函数）→ `1095e04a`（Ctrl+C/V 接入）→ docs 收尾（本目录+测试方案+feature-map/user-ops/问题单挂注记）。
