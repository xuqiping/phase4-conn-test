# SeedDance 2.0 视频生成 · 功能 README

> 全栈 MVP（plan Step1-7 全部落地）。C 类功能：用户地图 + 技术说明。
> 关联：[plan](../../docs/plans/SeedDance视频生成.plan.md) / [spec](../../docs/specs/SeedDance视频生成.md) / [开发进度1.md](开发进度1.md) / [FeatureMap](../../docs/feature-map/SeedDance视频生成.feature-map.md) / [UserOps](../../docs/user-ops/SeedDance视频生成用户操作手册.md) / [测试方案](../../docs/测试方案/SeedDance视频生成测试方案.md)

## 用户地图

| 维度 | 说明 |
|---|---|
| **谁用** | 持有 `media:gen` 权限的用户。gated 策略：**仅 admin 默认有**，普通 user 须 admin 在「角色权限」页按需授予（高成本能力，同 `knowledge:write`）。 |
| **场景** | ① 文生视频：输入提示词生成；② 图生视频：上传参考图作首帧 + 提示词生成。用于营销素材、分镜预览、创意辅助。 |
| **效益** | 平台首次具备媒体生成能力；抽象出「媒体生成 provider」骨架，后续 SeedDream 生图/无限画布/对话内生成/工作流节点复用同一套任务/存储/记账。 |
| **入口** | 侧边栏「视频生成」菜单（`/video-gen`，无权限不显示）。 |
| **限制** | 时长 4-15 秒（官方区间）；画面比例 16:9 等 7 选 + adaptive；分辨率 480p/720p/1080p/4K；单任务最长等待 10 分钟（超时 FAILED）；参考图 ≤8MB。视频走 SeedDance 2.0。 |

## 界面与流程（概览）

1. 选「文生视频/图生视频」→（图生则上传参考图）→ 填提示词 → 选时长/分辨率 → **提交生成**。
2. 页面 3 秒轮询任务态：排队中 → 生成中（通常 1-3 分钟）→ 已完成。
3. 完成后 `<video>` 内联播放 + 下载按钮；历史任务列表可回看任意已完成的视频。

## 技术说明

- **后端独立 `media/` 包**：provider 抽象（`MediaGenProvider` + `ArkSeedanceProvider`）/ 任务表 V54 / worker 纯 poll + SKIP LOCKED 崩溃恢复 / `MediaStorageService` 下载落 `stored_files` / usage Ark 真值优先、不返则按 Ark 官方 token/秒费率估算（720p=61760/s，5s≈30.88 万）/ REST API + ownership 过滤。**零回归**（新包新表新池，旧文件仅 2 处纯增量）。
- **前端**：`VideoGenView.vue` + `api/media.ts` + router + Sidebar。下载端点带 `@RequirePermission` 需 auth header → 前端 `fetchVideoBlob` 拉 blob 转 `URL.createObjectURL` 播放/下载（卸载时 revoke）。
- **权限 4 层显隐**：① 菜单 `v-if hasPermission('media:gen')` ② 页内 `canGen` 兜底 ③ 后端 `@RequirePermission` 403 ④ 路由 meta 仅 `requiresAuth`。
- **usage 口径隔离**：media token = 视频 pixel×帧×时长换算的「伪 token」，与 `llm_usage_logs`（文本分词）不可加总；后续 TokenUsage 落地后账单查询层 UNION 两表按 model_type 分列。

## 部署/配置必做

- Flyway 启动自动跑 V54；确认 `media:gen` 仅 admin 有（普通 user 按需授）。
- doubao provider 须已在 LLM 供应商配置：**API 端点填 base URL**（官方 `https://ark.cn-beijing.volces.com/api/v3` / 第三方网关如 ctaigw `https://ai.ctaigw.cn/v1`，代码不再硬编 /api/v3）+ Ark key + models 含 seedance 模型（如 `Cdance2.0`）。
- `application.yml` `media:` 段均可省（用默认：gen-enabled=true / max-duration=15 / max-res=720p / poll-ms=5000 / lock-minutes=5 / task-timeout-seconds=600）。

## 留项（Phase4，人工依赖）

- 真 Ark 端到端：提交→轮询→播放→下载；10 并发压测轮询 QPS；杀进程重启崩溃恢复；本地盘占用。
- provider status 映射单测（需引 MockWebServer 依赖，优先级低）。
