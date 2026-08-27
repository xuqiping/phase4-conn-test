# 人工测试遗留问题修复V设计（画布预览恢复 / 上游视频首帧 / 组池流水筛选导出）

> 对应问题文档：`workflow_output/人工测试问题/2x. 资产库和无限画布.md`（未解决 2 项）、`17x_项目组.md`（未解决 1 项）。
> 前序：修复设计 / II / III / IV（`人工测试遗留问题修复设计.md` 系列）。沿用 IV 结构惯例：根因 → 已确认口径 → 分域设计 → 汇总；不拆 PRD/architecture/db_schema 多文件（本轮零建表零迁移，单设计稿覆盖测试/安全/性能维度）。

## 1. 背景与缺陷清单

| # | 问题 | 根因（file:line） | 批 |
|---|---|---|---|
| 2x-1 | 上游面板视频节点只显「视」字，无首帧预览 | `upThumbSrc` 视频取 `coverPreviewUrl`（PropertyPanel.vue:967）——该字段**只有导演台封面**在写（CanvasView.vue:2239-2244），视频生成链从未产出 → 恒 null → 回退单字占位（:48） | A |
| 2x-2 | 副本创建后退出画布重进，内容消失 | 快照保存剥会话级 `previewUrl`（buildPersistSnapshot:2370-2376，objectURL 跨会话失效）；重进恢复两路：`hydratePreviews` 只管 image/audio（:2265）、`hydrateVideoPreviews` 只认 **taskId+mediaStatus**（:2274-2279）——C-8 副本脱钩清了 taskId（nodeClone.ts:18）→ 视频副本两路都不命中，永远空壳。图片副本 fileId 兜底理论可恢复（P3 实测确认） | A |
| 17x-1 | 组池流水无筛选、无导出 | overview 流水查询仅 page/size 分页（ProjectGroupQueryService.overview:75），前端流水 tab 零筛选零导出（ProjectGroupsView.vue:169-176） | B |

## 2. 已确认口径（用户 4 项决策，2026-08-27）

1. **2x-1 前端 video 标签直出首帧**：上游缩略图位渲染 `<video preload="metadata">`（浏览器自动显第一帧），零后端改动、零新存储；代价=每个上游视频卡一次视频头请求（上游 BFS 截断 50，实际少量，可接受）。
2. **17x-1 导出格式 CSV**：后端拼 UTF-8 BOM CSV（Excel 双击直开不乱码），零新依赖，沿用价表/JSONL 下载先例（ResponseEntity\<byte[]\> + @AuditLog）。
3. **17x-1 导出范围=按当前筛选全量**，上限 **5 万行**防拖垮，超限截断+CSV 尾注记行数。
4. **17x-1 仅管理侧可导**：组长/MANAGER/admin 可筛选+导出；成员流水 tab 维持 IV D3 受限口径（仅本人行、只读、余额列空），不加筛选行与导出按钮。

## 3. 画布域（2x 两项，纯前端）

### 3.1 上游视频首帧（2x-1）

**改动**：`PropertyPanel.vue` 上游卡缩略图位（:40-49）
- `type === 'video' && upMediaSrc(u)` → 渲染 `<video :src preload="metadata" muted playsinline>`（无 controls；`pointer-events:none` 让点击冒泡到外层按钮，单击开 Lightbox 播放不变）；CSS `object-fit: cover` 与 img 同规格。
- 图片分支不变；无媒体源仍回退「视」字占位（占位语义保留：无产物节点）。
- 不新增任何数据字段——`coverPreviewUrl` 保持导演台专用，不动。

**验证**：vitest PropertyPanel 上游渲染——video 上游项出 `<video>` 且 preload=metadata；无源仍占位；img 分支不回归。

### 3.2 副本重进恢复（2x-2）

**改动**：`CanvasView.vue` `hydrateVideoPreviews`（:2274）加 **fileId 兜底腿**：
- 现 taskId 腿优先（信息全：能补 resultFileId/mediaStatus/审计字段）；**无 taskId（或任务已查不到）但有 fileId** → `fetchCanvasPreview(fileId)` 拉 objectURL → `updateNodeData(n.id, { previewUrl, status:'success' })`；失败静默（口径同 :2268，不阻断加载）。
- 该兜底同时修复「原视频节点任务被清理后重进也空壳」的存量场景（同根因）。
- 图片副本按现状 fileId 链应可恢复——P3 用 Playwright 实测图片+视频副本各一，若图片也丢另查（快照 data 序列化路径），不留假设。

**验证**：Playwright 全链——原图/视频节点→创建副本→退出→重进→两副本内容在；vitest nodeClone 既有用例锁产物四件保留不回归。

## 4. 组池流水筛选+导出（17x-1）

### 4.1 筛选（overview 扩参）

**后端** `ProjectGroupQueryService.overview` / controller `GET /{id}/overview` 增可选参数：
- `keyword`：LIKE 匹配 **流水 remark ∪ 操作人备注(users.remark) ∪ 操作人姓名/账号**（E 轮 escapeLike 转义先例；users 量级小走子查询 `actor_user_id IN (SELECT id FROM users WHERE ...)`，ledger 主查仍走 LambdaQueryWrapper+idx_pgl_group_time）。
- `type`：14 种 CHECK 枚举白名单校验，非法 400。
- `actorUserId`、`from`/`to`（时间范围，同 outputs 的 OffsetDateTime 口径）。
- 权限不变：组长/MANAGER/admin 全量；MEMBER 路径**忽略筛选参数**维持仅本人行（后端强制 self，不信任前端）。

**前端** `ProjectGroupsView.vue` 流水 tab 顶加筛选行：关键词输入、类型下拉（中文标签：划拨/回收/消耗/退款/管理调整/组长兜底/成员配额…，沿用前端 TYPE 标签表）、操作人下拉（成员表数据源）、时间范围（NDatePicker range，复用产出 tab :274 先例）、「导出 CSV」按钮（`v-if canManage`）。筛选变更→分页重置 1→重查。成员视角不渲染筛选行（tab 现状）。

### 4.2 导出（新端点）

**后端** `GET /api/project-groups/{id}/ledger/export`（同筛选参数）：
- 权限 requireRole(MANAGER)+admin 恒放行（同 overview 管理侧）；**MEMBER 直接 403**（决策 4，比 overview 更紧——overview 成员可读本人行，export 不给）。
- 复用筛选查询不带分页，`LIMIT 50001` 截断：>5 万行取前 5 万，CSV 尾行注记`# 截断：共命中 N 行，仅导出前 50000 行`。
- CSV 列：时间/类型（中文标签，后端维护 type→label 映射表）/操作人（账号（姓名）·备注）/变动积分/变动后余额/关联（ref_type#ref_id）/备注。UTF-8 **BOM** 头；`Content-Disposition: attachment; filename=group-{id}-ledger-{yyyyMMdd-HHmmss}.csv`。
- `@AuditLog(module="projectgroup", action="ledger_export", targetType="project_group")` + `log.info` 导出行数/参数摘要（运维可追溯）。
- 金额格式：DECIMAL 原值去尾零（同前端 fmt 口径），负数原样带 `-`。

### 4.3 性能与边界

- 筛选查询：group_id+时间走既有 `idx_pgl_group_time`；keyword 子查询 users 全扫（~200 行量级，E 轮同口径）无瓶颈。
- 导出 5 万行 ≈5MB 一次性 byte[]（价表导出先例量级内），同步生成 <3s 预期；不引入流式/异步任务（超限即截断，不做分片）。
- 平衡口径：balance_after 是**组池视角**行（SELF_* 型行为个人名下腿，balance_after 为当时组池余量快照）——导出列名「变动后组池余额」明示口径，防对账误读；MEMBER_QUOTA_ADJUST/MEMBER_ALLOCATE 等成员腿行的 actor 可能是组长操作——按 actor 字段原样导出。

## 5. 汇总

- **迁移**：零迁移零数据订正，全部代码级可回滚。
- **联动点清单**（P2 逐条落测试方案）：①筛选任一变更→流水表重查+分页重置；②导出按钮→带当前筛选参数下载（含空筛选=全量）；③成员视角流水 tab 零变化（无筛选行/无导出/仅本人行）；④hydrate 兜底腿→副本与任务已删两类节点重进恢复；⑤上游 video 缩略图→单击 Lightbox 播放手势不回归（pointer-events 冒泡）。
- **安全**：type 白名单校验；keyword LIKE 转义；export MEMBER 403+审计；导出文件不含敏感凭据（纯流水数据）。
- **测试策略**：后端单测（筛选参数组装/CSV 拼装 BOM 转义截断/type 映射）+ IT（真 PG 造 ALLOCATE/CONSUME/BACKSTOP 流水→keyword/type/actor/时间筛选命中与排除、成员 403、导出内容断言、超限截断）；前端 vitest（PropertyPanel video 缩略图/占位/nodeClone 锁）+ vue-tsc。**需人工测试**（P4 Playwright 冒烟）：三项 UI 全链（上游视频首帧、副本重进、筛选+导出）。
- **运维考量**：export 审计+INFO 日志已随 chunk 埋；限流不加（组管理低频操作）；上限 5 万为硬顶配置常量（改值一处）。
- **性能目标**：overview+筛选 p95 <200ms@万行流水；export 5 万行 <3s。

## 6. P4 后续修复：上游视频 ▶ 播放标错位（2026-08-27 用户反馈）

**现象**（用户截图）：2x-1 首帧直出落地后，上游面板视频卡的 ▶ 播放标出现在**首帧旁边**（裸文本样式），应像参考区 `ReferencePreview` 一样**居中覆盖在首帧上**。

**根因**（双源探查证实）：P4 冒烟后补的 ▶ span（模板 [PropertyPanel.vue:51-60](../../../frontend/src/components/canvas/PropertyPanel.vue#L51-L60)，class=`prop-panel__up-play`）配套 SCSS 规则块 `&__up-play` **误嵌在 `&__up-thumb` 块内部**（[PropertyPanel.vue:1503-1514](../../../frontend/src/components/canvas/PropertyPanel.vue#L1503-L1514)）——SCSS 的 `&` 拼接产物为 `.prop-panel__up-thumb__up-play`，与模板 class 永不命中 → span 拿不到 `position:absolute`，按行内流排在 `<video>` 后面（44×44 flex 按钮内被挤到旁边）。参考区 `ref-preview__play` 生效因其规则在 `.ref-preview` **根层级**（ReferencePreview.vue:99-109）。

**修复**：`&__up-play` 规则块**移出** `&__up-thumb`，与 `&__up-card`/`&__up-meta` 同级（编译成 `.prop-panel__up-play` 命中模板）；属性值不变（absolute inset:0 / flex 居中 / #fff / text-shadow / pointer-events:none / z-index:3 高于 hover 放大 video 的 z2）；字号 13→**16px** 对齐参考区 `ref-preview__play`（用户口径「像参考里面的一样」）。

**验证**：vitest 既有断言（span 存在，PropertyPanel.test.ts:337）不回归；**人工视觉**——上游视频卡 ▶ 居中盖首帧、hover 1.6x 放大时 ▶ 仍锚定缩略框中心、单击仍开 Lightbox 播放。vitest 只验 DOM 不验 CSS 编译产物，此类 SCSS 坑只能人工验收。

**不做**：
- 截图另见描述文本 `@[node-1785921150587]]` 双右括号——前端零处产出 `@[` 字面量（token 生成仅 `@{{kind:id}}` 格式，MentionTextarea.vue:419 / mentionLogic.ts:72），系后端 LLM 分镜产出的 description 原文回显（CanvasView.vue:1254 存入节点 data，`upPromptSnippet` 原样 slice）——**数据痕迹非渲染 bug**，不在本期（如需治理：后端分镜 prompt 约束或 snippet 渲染 mention，另立议题）。
- 点击行为维持现状（上游缩略=Lightbox 不自动播；参考区=n-modal 自动播——两处手势差异为既有设计，不统一）。
