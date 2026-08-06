# SeedDance 视频生成 · Feature Map（功能-代码速查）

> 全栈 MVP 代码地图。表与 SQL 注解见文末（V54）。关联 [plan](../plans/SeedDance视频生成.plan.md) / [README](../../开发进度/SeedDance视频生成/README.md)。
>
> **v2（2026-08-06）**：① 支持按次选择视频模型（模型目录来自 `llm_providers` 表 category=MEDIA 的 provider，新模型零代码接入）；② 图生视频升级为「图+视频+音频 多模态参考生视频」（SeedDance 2.0：9图/3视频/3音频/总≤12，按模型能力动态校验）。新增 `GET /api/media/models`；提交体加 `model` + `attachments[]`；request_config JSONB 原样扩展（无新迁移）。

## 后端 `media/` 包（com.superprogrammer.media）

| 文件 | 作用 | 大白话 |
|---|---|---|
| `db/migration/V54__media_gen_tasks.sql` | 建任务表 + media:gen 权限 seed | 见文末表注解 |
| `provider/MediaGenProvider.java` | provider 接口：createTask/queryTask/getId | 「媒体生成厂商」统一合同，视频/图共用，未来 Seedream 也实现它 |
| `provider/ArkSeedanceProvider.java` | Ark SeedDance 实现：多模态 content 构建（image_url/video_url/audio_url + role=reference_*）+ `resolveArk(providerId)` 按任务落库的 provider 路由（多 MEDIA provider 并存各走各 endpoint/key，指纹含 providerId） | 真正打电话给火山方舟的「业务员」，照任务单上记的供应商回电；多模态素材按角色（参考图/运镜视频/音色音频）贴标签发给 Ark |
| `config/MediaModelCapability.java` | 单模型能力画像（maxImages/maxVideos/maxAudios/maxAttachments/比例/分辨率/时长/supportsGenerateAudio/videoDataUri） | 每个模型的「饭量卡」：能吃几张图、几个视频、几段音频 |
| `config/MediaModelCapabilityService.java` | 能力解析：内置前缀默认（seedance-2*=9/3/3/12、seedance-1*=仅图、未知=保守兜底）+ provider config JSON `capabilities:{"<modelId>":{...}}` 精确覆盖 | 新模型没登记先按保守量供应，管理员在供应商 config 里写一条就放开 |
| `service/MediaModelService.java` | 模型目录：聚合全部 ACTIVE MEDIA provider 的 models × 能力画像 → listModels；resolveProviderByModel（sortOrder 最小者优先） | 「菜单印刷机」：前端下拉和提交路由都从这里查哪个供应商供哪个模型 |
| `dto/MediaModelVO.java` | GET /models 出参（模型 id + 能力画像全量） | 前端动态表单的「数据源」 |
| `dto/AttachmentRef.java` | 参考附件入参（fileId + kind=image/video/audio） | 一张素材「提货单」 |
| `dto/MediaGenRequest.java` | provider 入参（+attachments 已解析 data URI 列表 +providerId 路由上下文；refImageUrl 保留旧首帧通道） | 喂给厂商的「订单」 |
| `dto/MediaGenResult.java` | provider 出参（status/resultUrl/usageTokens/errorMsg）统一状态机 | 厂商回的「回执」，屏蔽各厂商原生态差异；usage 兼容 completion_tokens\|\|total_tokens |
| `dto/MediaSubmitRequest.java` | REST 提交入参（+model 指定模型 +attachments ≤12 参考附件；refFileId 与 attachments 互斥） | 前端 POST 的请求体 |
| `dto/MediaTaskVO.java` | REST 视图（videoUrl 指向下载端点，非 Ark 临时 URL） | 前端轮询/列表拿到的对象 |
| `entity/MediaGenTask.java` | 任务实体（不继承 BaseEntity，append-only，带 locked_until/attempt） | 任务表的一行 |
| `mapper/MediaGenTaskMapper.java` | MyBatis-Plus mapper | DB 读写 |
| `config/MediaGenProperties.java` | `media.*` 配置（开关/上限/轮询/锁/超时/provider-name 默认 seedance） | 运维旋钮，都能省用默认 |
| `config/MediaTaskExecutorConfig.java` | `mediaTaskExecutor` Bean（core2/max4/queue100/AbortPolicy） | 专门跑视频任务的「小工队」，不挤占 chat/RAG/memory 线程 |
| `service/internal/MediaGenTaskTxService.java` | 全部 DB 写（claim/setArkTaskId/markSucceeded/markFailed/markDownloadFailed/renewLock）独立 bean | 事务边界，Ark 轮询（分钟级阻塞）必须在事务外，故拆出 |
| `service/MediaGenTaskService.java` | submit：模型→provider 反查路由（跨全部 ACTIVE MEDIA provider，模型不在列表 400）+ 能力校验（分类/总数上限、比例/分辨率/时长、音频开关）+ 附件归属+MIME 校验（防 IDOR）+ taskType 派生（attachments 非空→IMAGE2VIDEO） | 用户提交入口 + 「检票口」：超量/错类型/拿别人的票当场拒 |
| `service/MediaGenTaskWorker.java` | @Scheduled poll 认领（SKIP LOCKED）+ process（buildRequest 附件→data URI 按类型限大小→createTask→退避轮询 queryTask(arkTaskId, providerId)→下载→usage）+ 状态机 | 真正干活的「监工」，照抄 IndexJobWorker 纯 poll 模式，崩溃恢复免费 |
| `service/MediaStorageService.java` | downloadAndStore（Ark URL→stored_files source=MEDIA）+ readAsDataUri(fileId, userId, kind)（按类型限：图 8MB/音频 15MB/视频 50MB） | 「搬运工」：Ark 临时链接一过期就没，趁热下载到本地；参考素材转 data URI 喂 Ark |
| `service/MediaGenQueryService.java` | 读侧：get/list/loadForDownload，ownership 硬过滤 | 用户只能查/下自己的任务，admin 旁路看全量 |
| `controller/MediaGenController.java` | REST API + `GET /api/media/models` 模型目录（@RequirePermission 全端点）+ Content-Disposition 下载 | 前端接口入口 |

## 后端旧文件改动（纯增量，零回归）

- `file/service/FileStorageService.java`：新增 `storeStream(InputStream,...)` 单一存储咽喉点入口（media 下载复用）。
- `file/entity/StoredFileEntity.java`：新增常量 `SOURCE_MEDIA = "MEDIA"`（视频产物来源标记）。
- `llm/service/LlmProviderService.java`（v2）：新增 `getById(Long)`（worker 按任务落库 providerId 路由用）。
- `resources/application.yml`（v2）：`spring.servlet.multipart.max-file-size=60MB / max-request-size=65MB`（参考视频 ≤50MB 上传，破 Spring 默认 1MB/10MB）。

## 前端

| 文件 | 作用 |
|---|---|
| `api/media.ts` | submitVideo/getTask/listTasks/listModels/uploadAttachment（120s 超时）+ `fetchVideoBlob` + MediaModelVO/AttachmentRef 类型 + 状态标签/终态工具 |
| `views/VideoGenView.vue` | 生成页 v2：**模型下拉（按 provider 分组）驱动动态表单**——按能力画像渲染参考图/视频/音频三个上传区（x/上限 计数 + 客户端大小预检）+ 总计数 ≤maxAttachments；比例/分辨率/时长选项按模型过滤；generateAudio 仅支持的模型显示；切模型清空附件；历史表加模型列 |
| `router/index.ts` | 注册 `/video-gen` 路由（meta 仅 requiresAuth） |
| `components/Sidebar.vue` | 菜单项 `v-if="canGenVideo"`（hasPermission('media:gen')） |

## 关键调用链

**模型目录**：前端 `VideoGenView.loadModels` → `GET /api/media/models` → `MediaModelService.listModels`（遍历 ACTIVE category=MEDIA provider × models JSON × `MediaModelCapabilityService.resolve` 合并能力）→ 动态渲染表单。

**提交**：前端 `VideoGenView.onSubmit` → `mediaApi.submitVideo{prompt,...,model,attachments[]}` → `POST /api/media/video` → `MediaGenController.submit` → `MediaGenTaskService.submit`：model 非空→`resolveProviderByModel` 反查 provider（空→默认 provider 首模型）→ 能力校验 + 附件归属/MIME 校验 → attachments 非空派生 IMAGE2VIDEO → 建 PENDING 行（request_config 存 attachments JSON）。

**异步生成**：`MediaGenTaskWorker.poll`（@Scheduled）→ `claimBatch`(SKIP LOCKED) → `mediaTaskExecutor` 异步 `process` → buildRequest（附件 fileId→`readAsDataUri(kind)` 分类型限大小）→ `ArkSeedanceProvider.createTask`（content 数组：text + image_url/video_url/audio_url 带 role=reference_*；`resolveArk(providerId)` 按任务落库 provider 直连）→ 退避轮询 `queryTask(arkTaskId, providerId)` → SUCCEEDED→`downloadAndStore` → `resolveUsage` → `markSucceeded`。

**轮询/播放**：前端 3s 调 `GET /tasks/{id}` → SUCCEEDED 且 videoUrl → `fetchVideoBlob`（`GET /tasks/{id}/download` 带 token 拉 blob）→ `<video>` 播放。

**旧通道兼容**：画布连线/旧客户端走 `refFileId`（无 attachments）→ worker 旧分支转 data URI → content 发无 role 的 image_url（首帧语义）。旧 PENDING/RUNNING 行无需迁移。

---

## 表与 SQL 注解（V54，Flyway）

> 建表走 Flyway（`V54__media_gen_tasks.sql`）。已执行不可改，改结构加新版本。视频文件复用 V40 `stored_files`（写一行 source=MEDIA）。

**`media_gen_tasks`（媒体生成任务，append-only 日志+状态机）**
- 生活比喻：一张「视频订单流水」，从下单到出锅全程留痕，不撕单只归档。
- 关键字段：
  - `status` `PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED` — 订单状态机。
  - `ark_task_id` — 火山方舟那边的单号，轮询/崩溃恢复靠它续上。
  - `request_config` JSONB `{prompt,ratio,duration,resolution,watermark,generateAudio,refFileId?,attachments?[{fileId,kind}]}` — 用户填的参数（JSONB 无列约束,新字段自由存,V54 不动；v2 多模态 attachments 直接落此）。
  - `result_file_id` → `stored_files.file_id` — 出锅视频存本地的编号（Ark 临时链接有时效，必须下载落地）。
  - `tokens_cost` + `status_flag` `SUCCESS|ESTIMATED` — 用量记账：Ark 真值或像素费率估算（口径与文本 token 隔离，不可加总）。
  - `locked_until` + `attempt` — 「取餐号锁」：worker 用 `FOR UPDATE SKIP LOCKED` 认领，锁过期可被重认领，服务重启自动续跑（崩溃恢复）。
- 索引：`(user_id,created_at)` / `(status,created_at)` / `(ark_task_id)`。
- 权限 seed：`permissions('media:gen')` + 仅 admin 给（gated，普通 user 按需授）。
- 踩坑批注：① 不加 `deleted/version`（任务表不软删，靠归档）；② 不继承 BaseEntity（无自增 Long id，ID IDENTITY）；③ 不投 `llm_usage_logs`（media token=像素换算≠文本分词）。

---

## v2 附：模型与能力配置（无新表）

- **模型从哪来**：`llm_providers` 表 category=`MEDIA` 的 ACTIVE provider，其 `models` JSON 数组即可选模型；加新模型/新厂商 = 「全局模型供应商」页加/改一条 MEDIA provider，零代码。
- **能力怎么配**：内置前缀默认（`MediaModelCapabilityService`）；需微调时在 provider 的 `config` JSON 写
  `{"capabilities":{"doubao-seedance-2-0-260128":{"maxVideos":3,"maxAudios":3,"maxImages":9,"maxAttachments":12,"videoDataUri":true}}}`（只覆盖出现的字段）。
- **videoDataUri 风险开关**：官方 Ark 对 `video_url` 的 base64 data URI 支持未确认（部分渠道仅公网 URL）。若实测被拒，把该模型 config 里 `videoDataUri` 置 false——前端自动隐藏参考视频上传区，其余不受影响。
