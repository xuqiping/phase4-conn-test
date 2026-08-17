# 图片视频历史分页与预览缓存 · 功能 README

> 技术说明（A 类，无独立用户地图；操作步骤见 [user-ops](../../docs/user-ops/图片视频历史分页与预览缓存用户操作手册.md)）
> 计划：[plan](../../docs/plans/图片视频模块历史分页与预览缓存.plan.md) · 规格：[spec](../../docs/specs/图片视频模块历史分页与预览缓存设计.md) · 测试方案：[测试方案](../../docs/测试方案/图片视频模块历史分页与预览缓存测试方案.md)
> 解决问题：4x#2（视频历史无分页）、4x#3/6x#1（参考图无放大预览，重复项）、6x#2（图片无缓存重复拉取）

## 一、做了什么

1. **后端分页**：`GET /api/media/tasks` 支持 `page`/`pageSize`（白名单 5/10/20/50，缺省 10），返回 `PageResult{records,total,page,size,pages}`；旧 `limit` 参数兼容（pageSize 缺省时 limit 即页大小，1-100 校验）。count 与 list 同一组归一化参数，防 total 漂移。
2. **前端两页接入**：视频页 n-data-table `remote` 分页、图片页受控 `NPagination`；共享 `buildHistoryQuery` 拼装（`rangeType` 区分图片页整天区间/视频页精确时刻）。筛选变化重置第 1 页（L1），切条数回落第 1 页（L5）。
3. **共享预览组件**：`MediaLightbox`（Teleport 全屏灯箱：遮罩/Esc/关闭钮三路关闭）+ `HoverPreviewImage`（受控 popover 悬浮放大：300ms 防抖、重进重置、unmount 清计时器）。悬浮复用已加载 objectURL，零请求。
4. **两页预览接入**：图片页参考图 chip、视频页首尾帧/参考图瓦片 hover 放大 + 点击灯箱；参考视频瓦片点击开独立播放弹窗（不改表单）。
5. **会话内 LRU**：`fetchFilePreview` 走 `filePreviewCache`（80 条/128MB，缓存 Blob、命中新建 objectURL）；LRU 逻辑抽 `utils/blobLruCache.ts` 工厂。
6. **跨会话缓存**：`/api/files/{fileId}` 响应 `Cache-Control: no-cache, private` + ETag `"{fileId}-{size}"`，If-None-Match 命中 Spring MVC 自动 304 零 body；鉴权（load 归属校验）与 DATA_EXFIL 计数时序不动。

## 二、两级缓存为什么这么设计

- **会话内**（blobLruCache）：命中零请求。缓存 Blob 而非 objectURL——objectURL 被 revoke 后变死链，Blob 可重复 `createObjectURL`，各调用方 revoke 各自的 URL 互不影响。
- **跨会话**（ETag/304）：刷新页面后浏览器缓存失效但带 If-None-Match 回源，服务端指纹未变返 304 省 body。`no-cache`（每次回源再验证）而非 `no-store`（全禁缓存）——私有文件不能进共享缓存（`private`），但复验通过可复用本地副本。
- **fileId 不可变**：重新上传=新 fileId，文件被替换 size 变 → ETag 变，缓存自动失效。回答了 4x「进缓存的会更新吗」：会，靠 ETag 兜底（规格 §5.3）。
- **内存红线**：mediaBlobCache（媒体产物 6 条/256MB）与 filePreviewCache（/api/files 预览 80 条/128MB）分池，合计 ≤384MB。

## 三、破坏性变更与兼容

- `/api/media/tasks` 响应结构变更（破坏性）：`{list,total}` → PageResult 包裹。前后端**必须同版本发布/同回滚**（发布单注明）。消费方已全量盘点改造：后端仅 `MediaGenController`；前端 `media.ts`→`ImageGenView`/`VideoGenView` + 两个测试文件；`mediaEdit.ts` 是另一端点不受影响。
- ETag/缓存头无状态，可独立回滚。

## 四、关键位置

| 模块 | 位置 |
|---|---|
| 分页 SQL | `MediaGenTaskMapper.java`（selectHistory + countHistory，同 where 块同步维护） |
| 分页归一化 | `MediaGenQueryService.page()` + `resolvePageSize` |
| 查询拼装 | `frontend/src/api/media.ts` `buildHistoryQuery`（rangeType） |
| 预览组件 | `frontend/src/components/media/MediaLightbox.vue`、`HoverPreviewImage.vue` |
| LRU 工厂 | `frontend/src/utils/blobLruCache.ts`（限额构造注入，可测） |
| 文件缓存接线 | `frontend/src/api/file.ts` |
| 缓存头 | `FileController.get`（对齐 `MediaGenController.serveFile`） |

## 五、测试与遗留

- 单测：后端 `MediaGenQueryServiceTest` 28 例 + `FileControllerDownloadTest` 7 例；前端全量 78 文件 520 例绿（含组件 16 例、LRU 12 例、file wiring 4 例）。
- Phase 4 手测项（见测试方案）：C1 会话内零请求、C2 跨刷新 304、C3 逐出、C5 他人文件 403；历史首屏性能对比。
- 后续收敛机会：`media.ts` 手写 mediaBlobCache 迁移到 `blobLruCache` 工厂（本功能未动，避免无谓回归面）。
