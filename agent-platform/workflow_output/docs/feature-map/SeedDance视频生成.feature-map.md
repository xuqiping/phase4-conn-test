# SeedDance 视频生成 · Feature Map（功能-代码速查）

> 全栈 MVP 代码地图。表与 SQL 注解见文末（V54）。关联 [plan](../plans/SeedDance视频生成.plan.md) / [README](../../开发进度/SeedDance视频生成/README.md)。
>
> **v2（2026-08-06）**：① 支持按次选择视频模型（模型目录来自 `llm_providers` 表 category=VIDEO 的 provider，新模型零代码接入）；② 图生视频升级为「图+视频+音频 多模态参考生视频」（SeedDance 2.0：9图/3视频/3音频/总≤12，按模型能力动态校验）。新增 `GET /api/media/models`；提交体加 `model` + `attachments[]`；request_config JSONB 原样扩展（无新迁移）。
>
> **v3（2026-08-11）**：历史服务端筛选/参数与附件恢复、历史和资产媒体懒预览、多次 `@`、非阻塞持续轮询，以及实际 Provider 请求脱敏快照。外部契约见 [媒体生成 API](../api/媒体生成.md)。
>
> **v4（2026-08-11）**：Ark 参考视频改为短期 HMAC 签名 HTTPS URL；首/尾帧与全部参考媒体互斥；签名 URL 在 Provider 快照中整体脱敏。

## 后端 `media/` 包（com.superprogrammer.media）

| 文件 | 作用 | 大白话 |
|---|---|---|
| `db/migration/V54__media_gen_tasks.sql` | 建任务表 + media:gen 权限 seed | 见文末表注解 |
| `provider/MediaGenProvider.java` | provider 接口：createTask/queryTask/getId | 「媒体生成厂商」统一合同，视频/图共用，未来 Seedream 也实现它 |
| `provider/ArkSeedanceProvider.java` | Ark SeedDance 实现：一次构建实际 body，并由同一对象派生脱敏快照后 POST | 真正打电话给厂商的「业务员」；寄件清单留摘要，媒体正文不抄进账本 |
| `config/MediaModelCapability.java` | 单模型能力画像（maxImages/maxVideos/maxAudios/maxAttachments/比例/分辨率/时长/supportsGenerateAudio/videoDataUri） | 每个模型的「饭量卡」：能吃几张图、几个视频、几段音频 |
| `config/MediaModelCapabilityService.java` | 能力解析：内置前缀默认（seedance-2*=9/3/3/12、seedance-1*=仅图、未知=保守兜底）+ provider config JSON `capabilities:{"<modelId>":{...}}` 精确覆盖 | 新模型没登记先按保守量供应，管理员在供应商 config 里写一条就放开 |
| `service/MediaModelService.java` | 模型目录：聚合全部 ACTIVE VIDEO provider 的 models × 能力画像 → listModels；resolveProviderByModel（sortOrder 最小者优先） | 「菜单印刷机」：前端下拉和提交路由都从这里查哪个供应商供哪个模型 |
| `dto/MediaModelVO.java` | GET /models 出参（模型 id + 能力画像全量） | 前端动态表单的「数据源」 |
| `dto/AttachmentRef.java` | 参考附件入参（fileId + kind=image/video/audio） | 一张素材「提货单」 |
| `dto/MediaGenRequest.java` | provider 入参（图片/音频为 data URI，参考视频为签名 HTTPS URL；refImageUrl 保留旧首帧通道） | 喂给厂商的「订单」 |
| `dto/MediaGenResult.java` | provider 出参（status/resultUrl/usageTokens/errorMsg）统一状态机 | 厂商回的「回执」，屏蔽各厂商原生态差异；usage 兼容 completion_tokens\|\|total_tokens |
| `dto/MediaSubmitRequest.java` | REST 提交入参（+model 指定模型 +attachments ≤12 参考附件；refFileId 与 attachments 互斥） | 前端 POST 的请求体 |
| `dto/PreparedMediaRequest.java` | 实际 Provider body + 同源脱敏快照 | 防止事后重建的参数与真正发送内容不一致 |
| `dto/InputAttachmentVO.java` | 输入附件摘要 | 回显 fileId/类型/首尾帧角色/名称，不返回文件正文 |
| `dto/MediaTaskVO.java` | REST 视图；详情含提交参数和 Provider 脱敏快照 | `videoUrl/imageUrls` 是成功输出，不是输入附件 |
| `entity/MediaGenTask.java` | 任务实体（不继承 BaseEntity，append-only，带 locked_until/attempt） | 任务表的一行 |
| `mapper/MediaGenTaskMapper.java` | MyBatis-Plus mapper | DB 读写 |
| `config/MediaGenProperties.java` | `media.*` 配置（开关/轮询/锁/退避 + `reference` 公网地址/签名密钥/TTL） | 网络单次可超时重试；参考视频只有配好公网取件地址才开放 |
| `service/MediaReferenceUrlService.java` | 生成与验证 HMAC-SHA256 短期参考视频 URL | 给 Ark 一张限时、不可篡改的取件码 |
| `controller/MediaReferenceController.java` | 免 JWT 的签名视频回拉端点，只下发 `video/*` | 没有正确取件码或过期就拿不到文件 |
| `config/MediaTaskExecutorConfig.java` | `mediaTaskExecutor` Bean（core2/max4/queue100/AbortPolicy） | 专门跑视频任务的「小工队」，不挤占 chat/RAG/memory 线程 |
| `service/internal/MediaGenTaskTxService.java` | DB 写、请求快照保存、按 `locked_until` 安排下次查询 | 每次只查一下就把线程还回去，稍后再认领 |
| `service/MediaGenTaskService.java` | submit：模型→provider 反查路由（跨全部 ACTIVE VIDEO provider，模型不在列表 400）+ 能力校验（分类/总数上限、比例/分辨率/时长、音频开关）+ 附件归属+MIME 校验（防 IDOR）+ taskType 派生（attachments 非空→IMAGE2VIDEO） | 用户提交入口 + 「检票口」：超量/错类型/拿别人的票当场拒 |
| `service/MediaGenTaskWorker.java` | 每次认领只执行一次 create/query；RUNNING 或查询异常退避再入队；明确终态才结算 | 长任务不占死线程，服务重启后还能接着查 |
| `service/MediaStorageService.java` | downloadAndStore + 图片/音频 readAsDataUri；视频大小/MIME 在提交时校验 | 「搬运工」：产物落本地，轻量参考媒体转 data URI |
| `service/MediaGenQueryService.java` | 读侧：服务端提示词/时间筛选、详情参数/附件/快照、ownership 硬过滤 | 列表轻量，点详情才拿大 JSON；历史脏 data URI 也会再次脱敏 |
| `controller/MediaGenController.java` | REST API + `GET /api/media/models` 模型目录（@RequirePermission 全端点）+ Content-Disposition 下载 | 前端接口入口 |

## 后端旧文件改动（纯增量，零回归）

- `file/service/FileStorageService.java`：新增 `storeStream(InputStream,...)` 单一存储咽喉点入口（media 下载复用）。
- `file/entity/StoredFileEntity.java`：新增常量 `SOURCE_MEDIA = "MEDIA"`（视频产物来源标记）。
- `llm/service/LlmProviderService.java`（v2）：新增 `getById(Long)`（worker 按任务落库 providerId 路由用）。
- `resources/application.yml`（v2）：`spring.servlet.multipart.max-file-size=60MB / max-request-size=65MB`（参考视频 ≤50MB 上传，破 Spring 默认 1MB/10MB）。

## 前端

| 文件 | 作用 |
|---|---|
| `api/media.ts` | 提交/详情/带 `q/from/to/limit` 的历史筛选 + 新详情字段类型 |
| `components/media/MediaTaskRequestDetails.vue` | 两页签显示/复制平台参数和实际 Provider 脱敏快照 |
| `components/media/MediaTaskVideoPreview.vue` | 历史视频进入可视区才拉鉴权 blob，卸载释放 objectURL |
| `components/asset/AssetPickerMediaPreview.vue` | 资产选择器图片/视频懒预览，失败降级文字 |
| `components/canvas/MentionTextarea.vue` | chip 按内部 token 长度计算光标，可连续多次 `@` |
| `utils/mediaTaskPolling.ts` | 画布媒体无限、可取消轮询策略 |
| `views/VideoGenView.vue` | 动态表单 + 历史筛选/视频预览/参数恢复/请求参数入口；下线模型只读警告，失权附件禁止重提 |
| `router/index.ts` | 注册 `/video-gen` 路由（meta 仅 requiresAuth） |
| `components/Sidebar.vue` | 菜单项 `v-if="canGenVideo"`（hasPermission('media:gen')） |

## 关键调用链

**模型目录**：前端 `VideoGenView.loadModels` → `GET /api/media/models` → `MediaModelService.listModels`（遍历 ACTIVE category=VIDEO provider × models JSON × `MediaModelCapabilityService.resolve` 合并能力）→ 动态渲染表单。

**提交**：前端 `VideoGenView.onSubmit` → `mediaApi.submitVideo{prompt,...,model,attachments[]}` → `POST /api/media/video` → `MediaGenController.submit` → `MediaGenTaskService.submit`：model 非空→`resolveProviderByModel` 反查 provider（空→默认 provider 首模型）→ 能力校验 + 附件归属/MIME 校验 → attachments 非空派生 IMAGE2VIDEO → 建 PENDING 行（request_config 存 attachments JSON）。

**异步生成**：Worker 认领 → 图片/音频读取为 data URI、视频生成短期签名 HTTPS URL → Provider 一次构建 body/脱敏快照 → POST 前把快照写入 `request_config.providerRequestSnapshot` → 发同一 body。RUNNING/网络异常继续退避；明确成功才下载、计费、释放 inflight。

**历史/详情**：`GET /tasks?q&from&to&limit` 服务端过滤 → 点“查看”再调 `GET /tasks/{id}` → 恢复表单/附件并可查看两类请求 JSON。成功视频用鉴权 blob 播放；画布轮询不再按本地轮数误判超时。

**旧通道兼容**：画布连线/旧客户端走 `refFileId`（无 attachments）→ worker 旧分支转 data URI → content 发无 role 的 image_url（首帧语义）。旧 PENDING/RUNNING 行无需迁移。

---

## 表与 SQL 注解（V54，Flyway）

> 建表走 Flyway（`V54__media_gen_tasks.sql`）。已执行不可改，改结构加新版本。视频文件复用 V40 `stored_files`（写一行 source=MEDIA）。

**`media_gen_tasks`（媒体生成任务，append-only 日志+状态机）**
- 生活比喻：一张「视频订单流水」，从下单到出锅全程留痕，不撕单只归档。
- 关键字段：
  - `status` `PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED` — 订单状态机。
  - `ark_task_id` — 火山方舟那边的单号，轮询/崩溃恢复靠它续上。
  - `request_config` JSONB `{prompt,...,attachments?[...],providerRequestSnapshot?}` — 平台提交参数 + 新任务实际发送脱敏快照；媒体正文只保留摘要，无新迁移。
  - `result_file_id` → `stored_files.file_id` — 出锅视频存本地的编号（Ark 临时链接有时效，必须下载落地）。
  - `tokens_cost` + `status_flag` `SUCCESS|ESTIMATED` — 用量记账：Ark 真值或像素费率估算（口径与文本 token 隔离，不可加总）。
  - `locked_until` + `attempt` — 「取餐号锁」：worker 用 `FOR UPDATE SKIP LOCKED` 认领，锁过期可被重认领，服务重启自动续跑（崩溃恢复）。
- 索引：`(user_id,created_at)` / `(status,created_at)` / `(ark_task_id)`。
- 权限 seed：`permissions('media:gen')` + 仅 admin 给（gated，普通 user 按需授）。
- 踩坑批注：① 不加 `deleted/version`（任务表不软删，靠归档）；② 不继承 BaseEntity（无自增 Long id，ID IDENTITY）；③ 不投 `llm_usage_logs`（media token=像素换算≠文本分词）。

---

## v2 附：模型与能力配置（无新表）

- **模型从哪来**：`llm_providers` 表 category=`VIDEO` 的 ACTIVE provider，其 `models` JSON 数组即可选模型；加新模型/新厂商 = 「全局模型供应商」页加/改一条 VIDEO provider，零代码。
- **能力怎么配**：内置前缀默认（`MediaModelCapabilityService`）；需微调时在 provider 的 `config` JSON 写
  `{"capabilities":{"doubao-seedance-2-0-260128":{"maxVideos":3,"maxAudios":3,"maxImages":9,"maxAttachments":12,"videoDataUri":true}}}`（只覆盖出现的字段）。
- **参考视频部署开关**：Ark 已确认拒绝视频 data URI。模型 `maxVideos>0` 且 `MEDIA_REFERENCE_PUBLIC_BASE_URL` 为公网 HTTPS、`MEDIA_REFERENCE_SIGNING_KEY` 长度至少 32 时，模型目录才返回 `referenceVideoEnabled=true`；否则前端隐藏参考视频入口，后端 fail-closed。
