# SeedDance 视频生成 · 测试方案

> 功能需人工交互测试（UI 体验 + 真第三方 Ark 调用），故产出本方案。自动化能覆盖的（ownership/状态机/usage/能力校验/请求体构建）已落单测（44 绿，见下），此处聚焦**人工 E2E + 功能联动用例**。
>
> **v2（2026-08-06）**：新增模型选择 + 多模态参考（图/视频/音频）用例；单测从 15 → 44。

## 一、自动化测试（已落地，44 绿）

| 测试类 | 覆盖 | 用例数 |
|---|---|---|
| `MediaGenQueryServiceTest` | ownership 硬过滤（非 owner FORBIDDEN / admin 旁路 / owner 自查）+ videoUrl 不暴露 Ark 临时 URL + 下载前置校验 | 9 |
| `MediaGenTaskWorkerTest` | 状态机全分支（SUCCEEDED 真值 usage / 估算 / FAILED / createTask 抛 / downloadFailed / 空认领）+ **v2：attachments→dataURI 分类型转换 + providerId 透传 / 旧 refFileId 回归 / 附件读取失败** | 9 |
| `MediaModelCapabilityServiceTest`（v2 新增） | 前缀默认（seedance-2 标准/fast 1080p 封顶/mini、seedance-1 仅图、lite-i2v 4 图、未知保守兜底）+ config JSON 精确覆盖/仅精确 modelId 生效/坏 JSON 回退 | 8 |
| `MediaGenTaskServiceTest`（v2 新增） | 模型不在任何 provider 400 / 空 model 回退默认 / 10 图 400 / 13 附件总数 400 / 非法 kind / MIME 不符 / 他人附件 403 / admin 旁路 / attachments+refFileId 互斥 / 旧 refFileId 回归 / 不支持音频 400 / 超模型分辨率 400 | 13 |
| `ArkSeedanceProviderTest`（v2 新增） | buildCreateBody：纯文生仅 text 项 / 多模态 image_url+video_url+audio_url 带 role=reference_* / 旧首帧图无 role / attachments 优先于 refImageUrl / generate_audio 仅 true 时传 / 顶层平铺无 parameters 无 fps | 5 |

**待补**：provider status 映射单测（`parseQueryResult/mapStatus`，纯解析，worker 间接覆盖）。

## 二、人工 E2E（需真 Ark + 浏览器）

**前置**：Ark 账号开通 SeedDance 2.0；「全局模型供应商」配 MEDIA 类 provider（key/endpoint/视频模型列表）；admin 账号 + 一个普通 user 账号。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| E1 | 文生视频端到端 | admin 提交提示词 → 等待 | 排队→生成→完成，视频可播可下；历史新增一行 |
| E2 | 多模态参考生视频（v2） | 选 SeedDance 2.0 → 传 2 图 + 1 视频 + 1 音频，提示词按「图1/视频1/音频1」引用 → 提交 | 完成后视频内容与素材一致（运镜随参考视频、BGM 随参考音频） |
| E3 | 权限隔离·普通 user | 未授权 user 登录 | 看不到「视频生成」菜单；直访 `/video-gen` 显示「无权限」 |
| E4 | 授权后可见 | admin 授 user `media:gen` → user 刷新 | 菜单出现，可提交 |
| E5 | ownership 隔离 | user A 生成 → user B 查 A 的任务 ID | 403/查不到（普通 user 只看自己） |
| E6 | admin 全量 | admin 看历史 | 含所有用户任务 |
| E7 | 移动端 | 390px 宽访问 | 单列堆叠，表单/播放/历史可用 |
| E8 | 10 并发压测 | 同时提交 10 任务 | Ark 查询 QPS 可控（退避轮询）；无任务丢失 |
| E9 | 崩溃恢复 | 任务 RUNNING 中杀后端进程重启 | 下次 poll 自动续跑未完任务（不需 @PostConstruct） |
| E10 | 模型选择（v2） | 下拉切换不同模型 | 上传区/比例/分辨率/时长/音频开关按模型能力动态变化；历史表「模型」列显示所用模型 |
| E11 | 附件上限（v2） | 传 10 张图 / 合计 13 个附件 | 上传组件按 :max 拦截；绕过前端直接 POST → 400「参考图超限」/「附件总数超限」 |
| E12 | 参考视频 data URI 风险验证（v2，最高优先） | 传 1 个参考视频提交 | 若 Ark 拒 video base64 → FAILED 且错误可读；此时在该模型 provider config 置 `videoDataUri:false`，前端视频上传区自动隐藏 |
| E13 | 多 MEDIA provider 路由（v2） | 配两条 MEDIA provider（不同 endpoint/key），各含不同模型 | 提交按模型路由到对应 provider；任务中途删 provider → FAILED「供应商已停用或删除」 |
| E14 | 旧通道回归（v2） | 画布图→视频连线生成（走 refFileId） | 与 v1 行为一致（首帧图生视频） |

## 三、功能联动用例（对齐 plan 联动点清单，含正向/反向/半选/批量）

### 联动 1：provider 新增/编辑 ↔ 模型可用

| 用例 | 操作 | 预期联动 |
|---|---|---|
| 正向 | 改 doubao key 为有效 key → `/providers/reload` → 提交任务 | 任务正常 PENDING→SUCCEEDED |
| **反向** | 改 doubao key 为**失效 key** → 提交任务 | 任务 createTask 直接 **FAILED**（非卡 PENDING）；errorMsg 含 key 相关 |
| 半选 | 改 endpoint 但 key 不变 | WebClient 指纹变→重建客户端，按新端点调用 |

### 联动 2：任务 SUCCEEDED ↔ 视频下载落地

| 用例 | 操作 | 预期联动 |
|---|---|---|
| 正向 | 任务 SUCCEEDED | worker 即时下载 Ark URL → `stored_files`(source=MEDIA) 落地 → `result_file_id` 填 → 前端可播可下 |
| **反向** | mock 下载抛异常（断网/Ark URL 已过期） | 任务 **DOWNLOAD_FAILED**（ark_task_id 保留可重试）；前端显示「下载失败」 |
| 边界 | Ark SUCCEEDED 但无 `video_url` | `markDownloadFailed`（worker 留入口） |

### 联动 3：用户删账号 ↔ 历史任务

| 用例 | 操作 | 预期联动 |
|---|---|---|
| 正向 | 删除有历史任务的用户 | **不级联删**任务行（保留历史）；该用户任务 admin 仍可查；视频文件保留（后续清理任务处理） |
| 反向 | — | ownership 查询天然过滤：删除后该 user_id 不再匹配任何活跃会话 |

### 联动 4：任务超时 ↔ 状态机

| 用例 | 操作 | 预期联动 |
|---|---|---|
| 正向 | 任务轮询 > 10 分钟（mock Ark 持续 RUNNING） | 自动 **FAILED** + 释放 worker（locked_until 清空） |
| **反向/容错** | 超时 FAILED 后 Ark 侧实际已 SUCCEEDED | 下次 poll 若该行被重认领（锁过期）查询得 SUCCEEDED → 触发**补落地**（容错，状态机允许 PENDING/RUNNING 续轮询） |
| 批量 | 8 条任务同时 RUNNING 超时 | BATCH=8 一批，各自独立超时判 FAILED，互不影响 |

> **三处对齐**：plan 联动点清单（4 条）↔ 本方案联动用例（逐条 + 正向/反向/半选/批量）↔ UserOps 界面变化（提交→排队→生成→完成/失败）。

## 四、运维能力验证（随 chunk 已埋）

- [x] 可观测性：任务态变更打日志（taskId/userId/status/usageTokens）；失败 WARN。
- [x] 配置开关：`media.gen-enabled/max-duration/max-res/poll-ms/lock-minutes/task-timeout-seconds`。
- [x] 限流/降级：Ark connect 10s/response 30s 超时 + 失败有界重试 + 超时 FAILED。
- [ ] 运维入口（后续）：手动重试 FAILED 任务、清理过期视频脚本。
- [ ] 告警阈值（后续）：Ark 失败率/单用户日生成异常。
