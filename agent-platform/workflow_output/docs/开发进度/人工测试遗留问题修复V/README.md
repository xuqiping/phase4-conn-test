# 人工测试遗留问题修复V · 功能 README

> 2026-08-27。范围 3 缺陷：2x#1 上游视频显「视」字、2x#2 副本重进内容消失、17x#1 组池流水无筛选无导出。
> 规格 `docs/specs/人工测试遗留问题修复V设计.md` · 计划 `docs/plans/人工测试遗留问题修复V.plan.md`。
> commits：A `3c7926b0` / B `671bea37`（V156 迁移补档 `d4895c63`）/ P4 冒烟回归见进度2。
> 进度：[开发进度1.md](开发进度1.md)（P3）· [开发进度2.md](开发进度2.md)（P4 冒烟+review）。

## 技术说明

### A · 画布两项（commit 3c7926b0）
- **上游视频首帧**（PropertyPanel.vue）：上游面板视频卡有源时改 `<video preload="metadata" muted playsinline>` 直出首帧；video 层 `pointer-events:none` 保单击开 Lightbox 手势；无源回落「视」占位。
- **副本重进恢复**（CanvasView.vue）：持久化快照无 previewUrl，恢复链原只认 taskId，副本无 taskId → 空壳。补 fileId 兜底腿：无 taskId（或查询失败）→ fetchCanvasPreview 补 previewUrl+status；running 节点跳过；fileId 失效静默空壳；版本回滚链同享。

### B · 组池流水筛选+导出（commit 671bea37）
- **筛选**：overview 扩 keyword/type/actorUserId/from/to。keyword 走 `.apply("{0}")` 参数化子查询（备注 ∪ users 账号/姓名/备注三 LIKE，`\ % _` 预转义+`ESCAPE '\'`——inSql 无参数绑定弃用）；type 14 种白名单，非法 400；MEMBER 路径忽略筛选维持 IV D3 口径。
- **导出**：GET `/{id}/ledger/export`，`@RequirePermission("project-group:manage")` + `@AuditLog(ledger_export)`；managerViewOf 403（成员直调也拦）；当前筛选全量，orderByDesc(id) LIMIT 50001 判截断+selectCount 注记真值；selectBatchIds 批量补操作人防 N+1；CSV：U+FEFF BOM（Excel 直开不乱码）+ RFC4180 转义 + CRLF；log.info 全参。
- **前端**：ProjectGroupsView 流水 tab 筛选行（关键词/类型/操作人/时间/清空）+ 导出按钮 blob 下载；LEDGER_TYPE（前）与 TYPE_LABEL（后）口径交叉注释互指。
- **测试**：单测 16/16（含 50001 真数据截断、wrapper SQL 段+参数转义断言）+ 真 PG IT 6/6（`@Tag("integration")` profile it，jdbc 造组/成员/流水全链真 SQL——`.apply` 子查询语法错只有真库抓得住）。

### C · 文档勾销
2x/17x 问题文档「未解决→已解决（修复V）」；三页 feature-map+user-ops「2026-08-27 增补（修复V）」节；测试方案 `docs/测试方案/人工测试遗留问题修复V测试方案.md`（L1-L6 联动用例，P4 用）。

## 验证基线

vue-tsc 0 / vitest 848（基线 846+新增 2）/ 后端单测+IT 全绿。P4 Playwright 冒烟 L1-L6 全过（L3/L6 口径偏差如实记档），详见 [开发进度2.md](开发进度2.md)。
