# 规格规格 · SeedDance 2.0 视频生成（全栈 MVP）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：本会话 Phase0 分析 + 用户决策（2026-08-03）。主 PRD 见 [PRD.md](PRD.md)。
> 配套计划：[../plans/SeedDance视频生成.plan.md](../plans/SeedDance视频生成.plan.md)。
> ≤5000 tokens。

## 1. 项目概述
- **定位**：引入火山方舟 **SeedDance 2.0** 视频生成能力（文生视频/图生视频），全栈落地——后端 provider + 异步任务 + 本地存储 + REST API + 前端独立生成页。架构层抽象出「媒体生成 provider」，使后续 SeedDream 生图、首尾帧、视频续写能复用同一套任务/存储/采集骨架。
- **背景与动机**：平台现无任何媒体生成能力。`LlmProviderInterface` 只有 chat/chatStream/embed（同步/流式文本协议），无异步任务型生成。SeedDance 走 Ark 任务端点（建任务→轮询→取结果），与 chat 协议完全不同，不能塞进现有 provider。用户要在独立页/对话/工作流多处触发，MVP 先做独立页 + API，其余入口后续子 plan。
- **成功指标**：用户提交生成请求 → 异步轮询 Ark → 视频落地本地存储 → 前端播放/下载；token 用量（Ark 像素公式算）100% 记账；零主链路回归（生成走独立池，不扰 chat/RAG/memory）。

## 2. 用户故事
- 作为**管理员**，我在独立视频生成页输入 prompt（可选参考图）→ 提交 → 看进度 → 播放/下载生成的视频。
- 作为**被授权用户**，我同样能在独立页生成视频（权限 gated，admin 按需授）。
- 作为**管理员**，我看自己/全量的历史生成任务（ownership：普通用户只看自己）。
- 作为**开发者**，我通过 REST API 程序化调用视频生成（为后续对话集成/工作流节点铺路）。
- 作为**平台所有者**，每条生成的 token 消耗记账，便于后续成本核算（与文本 token 口径隔离，不混算）。

## 3. 功能需求
| 编号 | 功能 | 描述 | 优先级 | MVP |
|---|---|---|---|---|
| SD-1 | MediaGenProvider 抽象 + Ark SeedDance 实现 | 任务型生成 provider 接口（createTask/queryTask）；ArkSeedanceProvider 走 `{base}/contents/generations/tasks`，base 取 doubao provider endpoint（可配：官方 `/api/v3` / 网关 `/v1`），body 顶层平铺官方 2.0 契约（model/content/ratio/duration/resolution/watermark/generate_audio，无 parameters 包裹无 fps） | P0 | 是 |
| SD-2 | 异步任务表 + worker 轮询 | `media_gen_tasks` append-only + 状态机（PENDING→RUNNING→SUCCEEDED/FAILED）+ 退避轮询 + 崩溃恢复 | P0 | 是 |
| SD-3 | 视频本地存储 | 任务 SUCCEEDED 即下载 Ark 临时 URL 落 `stored_files`（V40 复用），不长期依赖临时链接 | P0 | 是 |
| SD-4 | REST API | 创建/查询/列表/下载，ownership 过滤 | P0 | 是 |
| SD-5 | usage 记账 | `media_gen_tasks.tokens_cost`（像素公式），解耦 llm_usage_logs | P0 | 是 |
| SD-6 | 前端独立生成页 | `/video-gen`：prompt+图→提交→轮询→播放下载+历史列表 | P0 | 是 |
| SD-7 | provider 配置 + 权限 | Ark key 复用 doubao（AES 解密）；`media:gen` 权限 gated（仅 admin 默认，照 V19） | P0 | 是 |
| SD-8 | 无限画布分镜编排页 | 分镜节点拖拽→每节点一生成任务→拼接（参考 HappyHorse/Sora storyboard） | P1 | 否 |
| SD-9 | 对话内生成 | media 消息类型，聊天触发 | P1 | 否 |
| SD-10 | 工作流 MEDIA_GEN 节点 | 编排引擎新增节点类型 | P1 | 否 |
| SD-11 | SeedDream 5.0 lite 生图 | 复用 MediaGenProvider，Ark Seedream 流式 SSE | P1 | 否 |
| SD-12 | 首尾帧/视频续写/多模态参考 | SeedDance 高级能力 | P2 | 否 |

> scope 决策：**MVP 只做单镜头文生+图生视频**。无限画布/多镜头/对话集成/工作流节点/生图/续写全部后续子 plan，本规格仅留 FR 编号占位。

## 4. 非功能需求
- **性能**：
  - 生成走独立 `mediaTaskExecutor`（core2/max4/queue100/AbortPolicy），**不复用 memory/usage/chat 线程池**，零互饿。
  - 轮询退避（首查 5s→指数→30s 封顶），单任务最长 10min 超时 FAILED；Ark 查询 QPS 可控。
  - 视频流式下载落盘（不全部 load 内存）；MVP 限 duration≤10s / 分辨率≤720p 控盘与成本。
  - `media_gen_tasks` 索引 `(user_id,created_at)`/`(status,created_at)`/`(ark_task_id)`。
- **安全**：
  - 权限 gated：`@RequirePermission("media:gen")`，仅 admin 默认有，普通用户须 admin 按需授（同 V19 `knowledge:write`）。
  - 前端 4 层显隐：菜单 `v-if="hasPermission"` + 按钮禁用 + 路由 `requiresAuth` + API 403 兜底。
  - ownership 硬过滤：用户只能查/下载自己的任务（`WHERE user_id=current`）。
  - 输入校验：prompt 长度上限、duration∈[1,10]、分辨率白名单、参考图大小/类型校验。
  - Ark key 复用现有 AES 加密存储，不明文落日志；下载端点 Content-Disposition 防 inline。
  - 失败固定话术，不透传 e.getMessage()。
- **可观测性**：任务态变更打日志（taskId/userId/status/usage）；失败 WARN 计数。
- **可回滚**：migration 建表附 drop；列无外部依赖。
- **韧性**：服务重启→`@PostConstruct` 扫 RUNNING 行重新挂轮询（崩溃恢复，同 IndexJob）；下载失败→DOWNLOAD_FAILED 可重试。

## 5. 架构
生成链路：
```
前端 VideoGenView / REST API 调用方
        │ POST /api/media/video (prompt + 可选 refImage, JWT)
        ▼
   MediaGenController ── MediaGenTaskService.submit()
        │ 建 media_gen_tasks(PENDING) + 投 mediaTaskExecutor
        ▼
   MediaGenTaskWorker（异步, 退避轮询）
        │  ArkSeedanceProvider.createTask() → ark_task_id
        │  loop: queryTask(ark_task_id) until 终态/超时
        │
   ├─ SUCCEEDED → MediaStorageService.downloadAndStore(url) → stored_files(V40) → result_file_id
   │              → 写 tokens_cost（Ark usage 或像素公式估算）
   └─ FAILED/TIMEOUT → status=FAILED + error_msg
        │
   前端轮询 GET /tasks/{id} → SUCCEEDED 返 <video> 播放/下载
```
**关键技术决策**：
1. **新 MediaGenProvider 接口，不污染 LlmProviderInterface**：任务型生成协议（create→poll→result）与 chat/embed 同步/流式协议本质不同，强行塞进 chat provider 会扭曲抽象。
2. **抽象留位**：MediaGenProvider 设计成 video+image 通用，后续 SeedDream 生图零架构改（仅新 impl）。
3. **复用 IndexJob 异步模式**：任务表 + worker + 退避轮询 + `@PostConstruct` 崩溃恢复，平台已验证模式（RAG 索引）。
4. **Ark key 复用 doubao provider**：同账号一把 key 通吃 Ark 所有端点（chat/embed/video/image），AES 解密复用，不新配 key。
5. **视频即时落地本地**：Ark 返回 OSS 临时 URL 有时效，SUCCEEDED 立即下载存 `stored_files`，只依赖本地路径。
6. **usage 解耦 llm_usage_logs**：media token = 像素×帧×时长换算，与文本 token（分词）口径不同不可加总；media_gen_tasks 自带 `tokens_cost` 独立记账。后续 TokenUsage 落地后，账单查询层 UNION 两表按 model_type 分列。
7. **权限 gated**：高成本能力，普通用户默认无权，admin 按需授（同 knowledge:write 策略）。

## 6. 数据模型
```sql
-- media_gen_tasks：媒体生成任务（append-only 日志 + 状态机）
CREATE TABLE media_gen_tasks (
  id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
  user_id       BIGINT,                       -- nullable：系统调用无 user
  provider_id   BIGINT NOT NULL,              -- llm_providers.id（Ark provider）
  model         VARCHAR(128) NOT NULL,        -- doubao-seedance-2.0 等
  task_type     VARCHAR(16) NOT NULL,         -- TEXT2VIDEO | IMAGE2VIDEO
  status        VARCHAR(20) NOT NULL DEFAULT 'PENDING',  -- PENDING|RUNNING|SUCCEEDED|FAILED|DOWNLOAD_FAILED
  ark_task_id   VARCHAR(128),                 -- Ark 返回的任务 id（轮询用）
  request_config JSONB NOT NULL,              -- {prompt, duration, resolution, ref_file_id?}
  result_file_id BIGINT,                      -- → stored_files.id（SUCCEEDED 后填）
  tokens_cost   INT,                          -- Ark usage.total_tokens 或像素公式估算
  cost          DECIMAL(12,6),                -- nullable：MVP 不折，价表后回填
  status_flag   VARCHAR(16) DEFAULT 'SUCCESS', -- SUCCESS | ESTIMATED | FAILED
  error_msg     VARCHAR(256)                  -- 失败原因（截断）
);
CREATE INDEX idx_mgen_user_time  ON media_gen_tasks(user_id, created_at);
CREATE INDEX idx_mgen_status_tm  ON media_gen_tasks(status, created_at);
CREATE INDEX idx_mgen_ark_task   ON media_gen_tasks(ark_task_id);

-- 复用 V40 stored_files：视频文件写一行 type=VIDEO（不新建表）
-- 复用 V1 permissions / role_permissions：seed media:gen（照 V19 模板）
```
**字段说明**：
- `ark_task_id`：Ark 建任务后返回，worker 用它轮询；重启恢复也靠它续查。
- `result_file_id`：指向 `stored_files.id`，SUCCEEDED 下载完成后填；下载失败留空 + status=DOWNLOAD_FAILED 可重试。
- `tokens_cost`：Ark 返 `usage.total_tokens` 用真值（status_flag=SUCCESS）；不返则按公式 `(in时长+out时长)秒 × 宽 × 高 × 24 / 1024` 估算（status_flag=ESTIMATED）。
- `request_config` JSONB：存原始请求参数（prompt/duration/resolution/ref_file_id），便于复跑与审计；不存 Ark 临时响应。
- 软删/审计四字段：`created_at`/`updated_at` 加；`deleted` 不加（append-only 任务表，不删，靠归档清理）。

## 7. 测试策略
**单元测试**：
- `ArkSeedanceProvider`：mock WebClient（create→返 taskId；query→PENDING/RUNNING/SUCCEEDED/FAILED 各分支；解析 video_url + usage）。
- 状态机：PENDING→RUNNING→SUCCEEDED/FAILED/DOWNLOAD_FAILED 全分支。
- 崩溃恢复：启动扫 RUNNING → 重新挂轮询。
- usage 两分支：Ark 返 usage（SUCCESS）vs 不返（ESTIMATED 像素公式，已知规格 5s/720p/24fps≈30.88万 token 断言偏差<1%）。
- 池满：注入 AbortPolicy → 断言不抛回主线程。

**集成测试**：
- 真 PG：提交任务 → 轮询 mock Ark → SUCCEEDED → 视频 downloadAndStore 落 `stored_files` → `result_file_id` 非 null。
- ownership 过滤：普通用户查自己 OK，查他人返空。
- 权限：无 `media:gen` 用户调 API → 403。

**⚠️ 测试着重**：
1. **状态机 + 崩溃恢复**：杀进程重启 RUNNING 自动续轮询；超时 FAILED。
2. **Ark URL 时效**：SUCCEEDED 后立即下载落地；URL 过期前完成；下载失败可重试。
3. **零回归**：独立池/表/包，chat/RAG/memory 延迟对比改造前后无变化。

**手动/Playwright 冒烟**：
- admin 提交→轮询→播放→下载；历史列表。
- 普通 user 看不到菜单 + 直访 URL 403；admin 授权后可见可用。
- 真实 Ark SeedDance 2.0 端到端（确认 API 已开通）。

## 8. 边界与不做
- **MVP 不做无限画布/多镜头/对话集成/工作流节点/生图/视频续写**（SD-8..SD-12 全部后续子 plan）。
- **MVP 不折成本**（`cost` nullable，价表后回填）。
- **不做配额/超限拦截**（纯生成 + 记账，非真计费扣费）。
- **不做实时推送账单**（前端轮询查库，非 WS 推送进度——MVP 轮询够用，WS 后续）。
- **不存 Ark 原始响应/临时 URL**（只存下载后的本地 fileId + request_config）。
- **不做 OSS/对象存储**（MVP 本地 FileStorageService；数据量大后后续迁 OSS）。
- **不接 llm_usage_logs**（口径不同，独立记账；统一账单待 TokenUsage 落地后查询层 UNION）。

## 9. 变更记录
| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-03 | 特性规格建立（Phase0 分析 + 决策审定 + 前沿调研） | 新增 SeedDance 视频生成功能 |
| 2026-08-06 | v2：① 按次选择视频模型（目录来自 llm_providers category=MEDIA，`GET /api/media/models` 下发能力画像）；② 图生视频升级为图+视频+音频多模态参考（SeedDance 2.0：9图/3视频/3音频/总≤12，attachments 落 request_config JSONB，无新迁移；refFileId 旧通道保留） | 客户迭代需求：多模型扩展 + 多模态参考 |

## 10. 术语表
| 术语 | 大白话 | 案例 |
|---|---|---|
| 任务型 API | 提交后异步轮询拿结果（非同步返） | SeedDance：建任务→查任务→拿视频 URL |
| MediaGenProvider | 媒体生成 provider 抽象（视频/图共用） | SeedDance/Seedream 都实现此接口 |
| 退避轮询 | 查询间隔越来越长省请求 | 5s→10s→20s→30s 封顶 |
| 崩溃恢复 | 服务重启后未完任务自动续跑 | 启动扫 RUNNING 重新挂轮询 |
| media token | 视频按像素×帧×时长换算的"伪 token" | 5s/720p/24fps≈30.88万 token |
| gated 权限 | 默认不给，admin 按需授 | 同 knowledge:write，普通 user 须授权 |
| stored_files | V40 已有的本地文件登记表 | 视频文件写一行 type=VIDEO |
