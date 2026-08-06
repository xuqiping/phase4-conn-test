# SeedDance 视频生成 · Feature Map（功能-代码速查）

> 全栈 MVP 代码地图。表与 SQL 注解见文末（V54）。关联 [plan](../plans/SeedDance视频生成.plan.md) / [README](../../开发进度/SeedDance视频生成/README.md)。

## 后端 `media/` 包（com.superprogrammer.media）

| 文件 | 作用 | 大白话 |
|---|---|---|
| `db/migration/V54__media_gen_tasks.sql` | 建任务表 + media:gen 权限 seed | 见文末表注解 |
| `provider/MediaGenProvider.java` | provider 接口：createTask/queryTask/getId | 「媒体生成厂商」统一合同，视频/图共用，未来 Seedream 也实现它 |
| `provider/ArkSeedanceProvider.java` | Ark SeedDance 2.0 实现：WebClient POST 建任务/GET 轮询，doubao key AES 复用，指纹缓存 WebClient | 真正打电话给火山方舟的「业务员」，照 doubao 配的 key+端点(base URL 可配,兼容官方 /api/v3 与 ctaigw /v1 网关)，按官方契约(顶层平铺 model/content/ratio/duration/resolution/watermark/generate_audio,无 parameters 包裹无 fps)建任务→查任务→拿 video_url |
| `dto/MediaGenRequest.java` | provider 入参（prompt/ratio/duration/resolution/watermark/generateAudio/taskType/refImageUrl/model） | 喂给厂商的「订单」 |
| `dto/MediaGenResult.java` | provider 出参（status/resultUrl/usageTokens/errorMsg）统一状态机 | 厂商回的「回执」，屏蔽各厂商原生态差异；usage 兼容 completion_tokens||total_tokens |
| `dto/MediaSubmitRequest.java` | REST 提交入参（带校验：prompt 非空/duration 4-15/ratio+resolution 白名单） | 前端 POST 的请求体 |
| `dto/MediaTaskVO.java` | REST 视图（videoUrl 指向下载端点，非 Ark 临时 URL） | 前端轮询/列表拿到的对象 |
| `entity/MediaGenTask.java` | 任务实体（不继承 BaseEntity，append-only，带 locked_until/attempt） | 任务表的一行 |
| `mapper/MediaGenTaskMapper.java` | MyBatis-Plus mapper | DB 读写 |
| `config/MediaGenProperties.java` | `media.*` 配置（开关/上限/轮询/锁/超时） | 运维旋钮，都能省用默认 |
| `config/MediaTaskExecutorConfig.java` | `mediaTaskExecutor` Bean（core2/max4/queue100/AbortPolicy） | 专门跑视频任务的「小工队」，不挤占 chat/RAG/memory 线程 |
| `service/internal/MediaGenTaskTxService.java` | 全部 DB 写（claim/setArkTaskId/markSucceeded/markFailed/markDownloadFailed/renewLock）独立 bean | 事务边界，Ark 轮询（分钟级阻塞）必须在事务外，故拆出 |
| `service/MediaGenTaskService.java` | submit（建 PENDING 行） | 用户提交入口 |
| `service/MediaGenTaskWorker.java` | @Scheduled poll 认领（SKIP LOCKED）+ process（createTask→退避轮询→下载→usage）+ 状态机 | 真正干活的「监工」，照抄 IndexJobWorker 纯 poll 模式，崩溃恢复免费 |
| `service/MediaStorageService.java` | downloadAndStore（Ark URL→stored_files source=MEDIA）+ readAsDataUri（参考图→base64） | 「搬运工」：Ark 临时链接一过期就没，趁热下载到本地；参考图转 data URI 喂 Ark |
| `service/MediaGenQueryService.java` | 读侧：get/list/loadForDownload，ownership 硬过滤 | 用户只能查/下自己的任务，admin 旁路看全量 |
| `controller/MediaGenController.java` | REST API（@RequirePermission 全端点）+ Content-Disposition 下载 | 前端接口入口 |

## 后端旧文件改动（纯增量，零回归）

- `file/service/FileStorageService.java`：新增 `storeStream(InputStream,...)` 单一存储咽喉点入口（media 下载复用）。
- `file/entity/StoredFileEntity.java`：新增常量 `SOURCE_MEDIA = "MEDIA"`（视频产物来源标记）。

## 前端

| 文件 | 作用 |
|---|---|
| `api/media.ts` | submitVideo/getTask/listTasks/uploadRefImage + `fetchVideoBlob`（带 token 拉 blob 转 objectURL）+ 状态标签/类型/终态工具 |
| `views/VideoGenView.vue` | 生成页：文生/图生切换 + 参考图上传 + ratio/duration(4-15)/resolution(含4K)/水印/音频 选择 + 3s 轮询 + `<video>` 播放 + 历史列表 + 移动端 useBreakpoints + canGen 权限兜底 |
| `router/index.ts` | 注册 `/video-gen` 路由（meta 仅 requiresAuth） |
| `components/Sidebar.vue` | 菜单项 `v-if="canGenVideo"`（hasPermission('media:gen')） |

## 关键调用链

**提交**：前端 `VideoGenView.onSubmit` → `mediaApi.submitVideo` → `POST /api/media/video` → `MediaGenController.submit` → `MediaGenTaskService.submit`（建 PENDING 行）。

**异步生成**：`MediaGenTaskWorker.poll`（@Scheduled）→ `claimBatch`(SKIP LOCKED) → `mediaTaskExecutor` 异步 `process` → `ArkSeedanceProvider.createTask`（落 arkTaskId）→ 退避轮询 `queryTask` → SUCCEEDED→`MediaStorageService.downloadAndStore`（落 stored_files）→ `resolveUsage`（Ark 真值/费率估算）→ `markSucceeded`。

**轮询/播放**：前端 `VideoGenView` 3s 调 `GET /tasks/{id}` → SUCCEEDED 且 videoUrl → `fetchVideoBlob`（`GET /tasks/{id}/download` 带 token 拉 blob）→ `<video>` 播放。

---

## 表与 SQL 注解（V54，Flyway）

> 建表走 Flyway（`V54__media_gen_tasks.sql`）。已执行不可改，改结构加新版本。视频文件复用 V40 `stored_files`（写一行 source=MEDIA）。

**`media_gen_tasks`（媒体生成任务，append-only 日志+状态机）**
- 生活比喻：一张「视频订单流水」，从下单到出锅全程留痕，不撕单只归档。
- 关键字段：
  - `status` `PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED` — 订单状态机。
  - `ark_task_id` — 火山方舟那边的单号，轮询/崩溃恢复靠它续上。
  - `request_config` JSONB `{prompt,ratio,duration,resolution,watermark,generateAudio,refFileId?}` — 用户填的参数（JSONB 无列约束,新字段自由存,V54 不动）。
  - `result_file_id` → `stored_files.file_id` — 出锅视频存本地的编号（Ark 临时链接有时效，必须下载落地）。
  - `tokens_cost` + `status_flag` `SUCCESS|ESTIMATED` — 用量记账：Ark 真值或像素费率估算（口径与文本 token 隔离，不可加总）。
  - `locked_until` + `attempt` — 「取餐号锁」：worker 用 `FOR UPDATE SKIP LOCKED` 认领，锁过期可被重认领，服务重启自动续跑（崩溃恢复）。
- 索引：`(user_id,created_at)` / `(status,created_at)` / `(ark_task_id)`。
- 权限 seed：`permissions('media:gen')` + 仅 admin 给（gated，普通 user 按需授）。
- 踩坑批注：① 不加 `deleted/version`（任务表不软删，靠归档）；② 不继承 BaseEntity（无自增 Long id，ID IDENTITY）；③ 不投 `llm_usage_logs`（media token=像素换算≠文本分词）。
