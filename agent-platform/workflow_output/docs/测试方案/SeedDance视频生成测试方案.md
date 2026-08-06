# SeedDance 视频生成 · 测试方案

> 功能需人工交互测试（UI 体验 + 真第三方 Ark 调用），故产出本方案。自动化能覆盖的（ownership/状态机/usage）已落单测（15 绿，见下），此处聚焦**人工 E2E + 功能联动用例**。

## 一、自动化测试（已落地，15 绿）

| 测试类 | 覆盖 | 用例数 |
|---|---|---|
| `MediaGenQueryServiceTest` | ownership 硬过滤（非 owner FORBIDDEN / admin 旁路 / owner 自查）+ videoUrl 不暴露 Ark 临时 URL（未完成 null）+ 下载前置校验（NOT_FOUND/BAD_REQUEST） | 9 |
| `MediaGenTaskWorkerTest` | 状态机全分支（SUCCEEDED 真值 usage / 无 usage 按 720p=61760/s 估算=308800 / FAILED / createTask 抛 / downloadFailed / 空认领） | 6 |

**待补**：provider status 映射单测（`ArkSeedanceProvider.parseQueryResult/mapStatus` 私有 + WebClient 内建，需引 MockWebServer 或抽 parse 为包级可见，优先级低——纯解析，worker 间接覆盖）。

## 二、人工 E2E（需真 Ark + 浏览器）

**前置**：Ark 账号开通 SeedDance 2.0；doubao provider 配 key/endpoint/seedance 模型；admin 账号 + 一个普通 user 账号。

| # | 场景 | 步骤 | 预期 |
|---|---|---|---|
| E1 | 文生视频端到端 | admin 提交提示词 → 等待 | 排队→生成→完成，视频可播可下；历史新增一行 |
| E2 | 图生视频 | admin 上传参考图 + 提示词提交 | 完成后视频以参考图为首帧动起来 |
| E3 | 权限隔离·普通 user | 未授权 user 登录 | 看不到「视频生成」菜单；直访 `/video-gen` 显示「无权限」 |
| E4 | 授权后可见 | admin 授 user `media:gen` → user 刷新 | 菜单出现，可提交 |
| E5 | ownership 隔离 | user A 生成 → user B 查 A 的任务 ID | 403/查不到（普通 user 只看自己） |
| E6 | admin 全量 | admin 看历史 | 含所有用户任务 |
| E7 | 移动端 | 390px 宽访问 | 单列堆叠，表单/播放/历史可用 |
| E8 | 10 并发压测 | 同时提交 10 任务 | Ark 查询 QPS 可控（退避轮询）；无任务丢失 |
| E9 | 崩溃恢复 | 任务 RUNNING 中杀后端进程重启 | 下次 poll 自动续跑未完任务（不需 @PostConstruct） |

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
