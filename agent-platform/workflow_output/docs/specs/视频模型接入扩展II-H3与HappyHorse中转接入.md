# 规格 · 视频模型接入扩展 II（MiniMax H3 全家桶 + HappyHorse 1.1 三模型，经 ai.ctaigw.cn 中转）

> SDD 特性级规格（Phase 1 产出）。实现须与本文件对齐；冲突时改实现或改本文档（注明原因）。
> 来源：用户 2026-08-30 提出经中转接入两 provider + 四决策拍板（§3）+ 官网调研（§2 对照表）。
> 上游规格：[视频模型接入扩展.md](视频模型接入扩展.md)（MVR-1~8，provider 路由 / 分辨率字典 / SECOND 分档秒价槽均已落地）、[积分计费系统.md](积分计费系统.md)、[视频TOKEN分辨率分档计价.md](视频TOKEN分辨率分档计价.md)（V162 槽位先例）。

## 1. 背景与平台现状事实（2026-08-30 代码探查）

- **三适配器已在**（MVR-5/6 已落地）：`ArkSeedanceProvider`（ID=`ark`）、`MinimaxVideoProvider`（ID=`minimax`）、`DashscopeVideoProvider`（ID=`dashscope`）；worker 按 `llm_providers.protocol` 路由（协议字段值 = 适配器 bean ID）。**本特性零新适配器**，全部是现有适配器的能力补齐 + 种子数据。
- **MinimaxVideoProvider 对中转零代码可用**：endpoint 填建任务完整 URL（`https://ai.ctaigw.cn/v1/video_generation`），查询地址推导 = 剥尾段 `/video_generation` 拼 `/query/video_generation` → `/v1/query/video_generation`，与中转查询路径吻合（[MinimaxVideoProvider.java:401-419](../../../backend/src/main/java/com/superprogrammer/media/provider/MinimaxVideoProvider.java#L401-L419)）。content[] 平铺协议、resolution 768P/2K 映射、duration 4-15、ratio 透传、`usage.total_seconds` → usageTokens，全部现成。
- **DashscopeVideoProvider 现仅支持 i2v 形态**（RF/MVR-6 按 happyhorse-1.1-i2v 写死）：`buildCreateBody` 强制"有且仅有 1 张 type=first_frame 首帧图"，无 `ratio` 透传，无 `reference_image` 类型（[DashscopeVideoProvider.java:165-221](../../../backend/src/main/java/com/superprogrammer/media/provider/DashscopeVideoProvider.java#L165-L221)）——t2v（纯文）与 r2v（多图参考）两形态进不来。
- **Dashscope 查询地址默认推导对中转是错的**：`deriveQueryBase` 无 `/api/v1/` 段时回落 `scheme://host + /api/v1/tasks` = `https://ai.ctaigw.cn/api/v1/tasks` ≠ 中转实际 `https://ai.ctaigw.cn/v1/tasks`（[DashscopeVideoProvider.java:428-454](../../../backend/src/main/java/com/superprogrammer/media/provider/DashscopeVideoProvider.java#L428-L454)）。**必须**在 provider config 显式配 `queryEndpoint`（覆盖机制已存在）。
- **能力层前缀默认不匹配三模型差异**（[MediaModelCapabilityService.java:199-212](../../../backend/src/main/java/com/superprogrammer/media/config/MediaModelCapabilityService.java#L199-L212)）：happyhorse 前缀 → 1图/0视频/0音频/≤1080p/3-15s（i2v 画像，t2v/r2v 不对）；minimax 前缀 → 9图/3视频/3音频/15总/768p+2k/4-15s（生成与 Context-IR 对，regeneration 不对）。**比例集 mismatch**：平台 `ALL_RATIOS` 7 值含 `adaptive` 无 `4:5/5:4/9:21`；HappyHorse 官方 9 值 `16:9,9:16,1:1,4:3,3:4,4:5,5:4,9:21,21:9` 无 `adaptive`；且 **i2v 官方无 ratio 参数**（宽高跟随首帧）。
- **计费链现状**：VIDEO+SECOND 模式 = `price_per_second_per_resolution[resolution] ?? price_per_second` × 秒，估价（`estimateVideoYuan`）与真实扣费（`videoCost`）同口径（V164/MVR-3）；VIDEO+TOKEN 模式**单 token 价无 input/output 分价**（[PricingService.java:386-404](../../../backend/src/main/java/com/superprogrammer/billing/service/PricingService.java#L386-L404)）——Context-IR 的输入/输出双价（5.8/23 每百万）表达不了，但 **CHAT kind 的 `textCost` 天生 input/output 分价**（[PricingService.java:126-127](../../../backend/src/main/java/com/superprogrammer/billing/service/PricingService.java#L126)）。估价 fail-closed（缺价拒提交，2026-08-25 硬闸）。
- **DTO 缺口**：`MediaGenRequest` 无 `sourceTaskId` 字段（再生成需要传源任务）；`MediaGenResult` 只有 `resultUrl` + `usageTokens` 单值（Context-IR 要文本结果 + prompt/completion 双 token）。
- **本地库现状**：`llm_providers` 10 行，VIDEO 仅 id=6 seedance/ark；无 minimax/dashscope 行；`pricing_rule` kind=VIDEO 仅 id=7（Cdance2.0 TOKEN）。下一迁移号 **V166**。

## 2. 官网 vs 中转对照表（用户核心要求：一致则保持，不一致列官网口径）

### 2.1 HappyHorse 1.1（官方=阿里云百炼 help.aliyun.com，中转=ai.ctaigw.cn）

官方三模型 id：`happyhorse-1.1-t2v` / `happyhorse-1.1-i2v` / `happyhorse-1.1-r2v`（另有 1.0 系，本期不接）。协议同构（input/parameters 包裹 + `X-DashScope-Async: enable` 异步头），差异只在路径前缀：官方 `/api/v1/*`，中转 `/v1/*`。

| 参数 | t2v（文生视频） | i2v（图生视频，现有实现） | r2v（多图参考生视频） |
|---|---|---|---|
| prompt | **必填**（非中文≤5000 / 中文≤2500 字符） | 可选 | 可选，多图用 `[Image N]` 指代 |
| input.media | **无**（纯文） | 恰 1 张 `type=first_frame`（JPEG/JPG/PNG/WEBP，宽高≥300px，宽高比 1:2.5~2.5:1，≤20MB，URL 或 base64 data URI） | **1-9 张 `type=reference_image`**（短边≥400px，≤20MB） |
| resolution | **720P/1080P**（默认 1080P，**无 480P**） | 480P/720P/1080P（默认 1080P） | **720P/1080P**（默认 1080P，无 480P） |
| ratio | 9 值默认 16:9 | **无此参数**（宽高跟随首帧，官方 FAQ 明确） | 9 值默认 16:9 |
| duration | [3,15] 整数默认 5 | [3,15] 默认 5 | [3,15] 默认 5 |
| watermark | 默认 **true**（右下角 "Happy Horse"） | 同左 | 同左 |
| seed | [0,2147483647] 可选 | 同左 | 同左 |

异步任务流：`POST {endpoint}` 返 `output.task_id`（24h 可查）→ `GET {tasks}/{task_id}` → `output.task_status`（PENDING/RUNNING/SUCCEEDED/FAILED/CANCELED）→ 成功 `output.video_url`（24h 有效，MP4 H.264 24fps）+ `usage.duration`（**计费秒数**）。

**中转端点**：建任务 `POST https://ai.ctaigw.cn/v1/services/aigc/video-generation/video-synthesis`，查询 `GET https://ai.ctaigw.cn/v1/tasks/{task_id}`（官方是 `/api/v1/tasks`，中转砍了 `/api` 前缀——这就是必须显式配 `queryEndpoint` 的原因）。

> ⚠️ 中转示例 curl 对 r2v 模型发的是 `type:"first_frame"`——与官方 r2v 参数表不符（官方用 `reference_image`）。本平台 provider 按**官方口径**发 `reference_image`；若中转实际只认 first_frame，属网关兼容层问题，人工验收时确认（§7 风险②）。

**计费对照**（官方百炼价目 = 中转价，一致 ✓）：

| 分辨率 | 官网价 | 中转价 | 采纳 |
|---|---|---|---|
| 480P | ¥0.45/秒（仅 i2v 有此档） | 未列 | **0.45**（按官网补齐，i2v 行） |
| 720P | ¥0.90/秒 | ¥0.90/秒 | 0.90 |
| 1080P | ¥1.20/秒 | ¥1.20/秒 | 1.20 |

（官网另有限时 6 折活动价 0.54/0.72——**不采纳**，按非折扣目录价配置，与中转结算口径一致；活动价属临时营销。）

### 2.2 MiniMax H3（官方=platform.minimax.io v2 API，中转=ai.ctaigw.cn /v1）

官方 host `api.minimax.io`、路径 `/v2/*`、model id `MiniMax-H3`；中转 host `ai.ctaigw.cn`、路径 `/v1/*`、model id `minimax-h3`。**协议 shape 完全同构**（平铺 JSON、content[] 多模态、`task_id` 建任务、查询 `task.*` 包裹、共享 Query Task 端点按 `task_type` 区分 `generation`/`h3_context_ir`/`regeneration`）。注意：官方 guide 页的 python 示例是老 v1 协议（prompt 平铺 + Hailuo 模型），H3 一律以 v2 API Reference 为准。

| 项 | 视频生成（已接） | H3-Context-IR（上下文理解增强） | 视频再生成（768P→2K 超分） |
|---|---|---|---|
| 端点（官方/中转） | `/v2/video_generation` `/v1/video_generation` | `/v2/h3_context_ir` `/v1/h3_context_ir` | `/v2/video_regeneration` `/v1/video_regeneration` |
| body.model | `MiniMax-H3` / `minimax-h3` | 同左（**中转传基础名**，平台侧模型 id 带后缀区分，见 HHX-4） | 同左 |
| 输入 | content[]（text/image_url/video_url/audio_url + role），首尾帧图各≤1、参考图≤9、参考视频≤3、参考音频≤3；i2v 与参考互斥 | 同生成（组合约束一致：t2v 纯文 / i2v 首尾帧 / r2v 参考，互斥）；请求体 ≤64MB | **无 content**：`source_task_id`（7 天内 succeeded 的本账户生成任务）或 `content` 带 `role=base_video` |
| 顶层参数 | `resolution` 必填 780P/2K 官方口径（v2 API 实为 768P/2K）、`duration` 必填 4-15、`ratio`（t2v 必填非 adaptive；i2v 恒 adaptive） | `duration` 必填 4-15、`ratio`（约束同生成）；**无 resolution** | `resolution` 必填**仅 `2K`**、`aigc_watermark` 默认 false |
| 查询结果 | `task.content.url` + `usage.total_seconds`（计费秒） | `task.content.prompt`（结构化增强提示词文本）+ `usage.{total_tokens, prompt_tokens, completion_tokens}` | `task.content.url` + `usage.{total_seconds, input_seconds:0, output_seconds, input_image_count:0}` |
| 输出 | 视频 URL | **纯文本**（不生成视频） | 2K 视频 URL |

**计费对照**：

| 项 | 官网价 | 中转价 | 采纳 |
|---|---|---|---|
| 生成·输出 768P 有声 | 官方国内站 ¥0.5/秒（国际站 paygo 页未列 H3 人民币价） | ¥0.5/秒 | 0.5 |
| 生成·输出 2K 有声 | 同上 | ¥0.8/秒 | 0.8 |
| 生成·输入视频/图片 | 官方未单列 | 768P ¥0.5/秒、2K ¥0.8/秒、图 ¥0.2/张 | **不建模**（§8 已知限制：SECOND 估价只算输出秒） |
| 再生成·输出 2K | 官方未单列 | ¥0.3/秒 | 0.3 |
| 再生成·输入 | 官方未单列 | 视频 ¥0.3/秒、图 ¥0.15/张 | 不建模（同上） |
| Context-IR·输入 | 官方未单列 | ¥0.0058/千 tokens = **5.8/百万** | 5.8 |
| Context-IR·输出 | 官方未单列 | ¥0.023/千 tokens = **23/百万** | 23 |

（生成 768P/2K 价此前已核实与官方国内站一致；Context-IR 与再生成价官方页未见公开明细，以中转文档为准——对照口径已注明来源。）

## 3. 用户决策（2026-08-30 拍板）

| # | 问题 | 决策 |
|---|---|---|
| Q1 | HappyHorse 接入形态 | **t2v + i2v + r2v 三模型全接**（用户确认"是不是还有 t2v"——官方存在，纳入） |
| Q2 | 计费数字口径 | **按用户给的中转价**（= 官网非折扣目录价，见 §2 对照） |
| Q3 | 落地方式 | **Flyway 种子迁移 V166**（provider 两行 + 价表行随发版自动就位，用户仅填 key） |
| Q4 | H3 附属两端点（Context-IR / 再生成） | **一起做**（本期后端全链路 + 前端入口） |

## 4. 功能需求

| 编号 | 需求 | 说明 | 优先级 |
|---|---|---|---|
| HHX-1 | HappyHorse provider 种子行 | `llm_providers` 新行：name=`happyhorse`、category=VIDEO、protocol=`dashscope`、apiEndpoint=`https://ai.ctaigw.cn/v1/services/aigc/video-generation/video-synthesis`、config=`{"queryEndpoint":"https://ai.ctaigw.cn/v1/tasks"}`（**必须显式**，默认推导错）、models=`["happyhorse-1.1-t2v","happyhorse-1.1-i2v","happyhorse-1.1-r2v"]`、**apiKeyEnc 空**（用户自填） | P0 |
| HHX-2 | MiniMax provider 种子行 | name=`minimax-h3`、category=VIDEO、protocol=`minimax`、apiEndpoint=`https://ai.ctaigw.cn/v1/video_generation`、config=`{"queryEndpoint":"https://ai.ctaigw.cn/v1/query/video_generation"}`（推导虽对，显式更稳）、models=`["minimax-h3","minimax-h3-context-ir","minimax-h3-regeneration"]`、apiKeyEnc 空 | P0 |
| HHX-3 | Dashscope 适配器三形态 | `buildCreateBody` 按模型 id 后缀分支：`-t2v`→无 media（有图附件 fail-fast）+ `parameters.ratio` 透传（空默认 16:9）+ resolution 仅 720P/1080P；`-i2v`→现状不变（1 张 first_frame，无 ratio）；`-r2v`→全部 image 附件转 `type=reference_image`（1-9 张，越界 fail-fast）+ ratio 透传。无后缀旧 id 维持 i2v 行为（回归兼容） | P0 |
| HHX-4 | MiniMax 附属端点路由 + 后缀剥除 | `MinimaxVideoProvider` 建任务 URL 按平台模型 id 路由：含 `-context-ir` → 剥 `/video_generation` 拼 `/h3_context_ir`；含 `-regeneration` → 拼 `/video_regeneration`；config 可加 `contextIrEndpoint`/`regenerationEndpoint` 覆盖（queryEndpoint 同款机制）。**body.model 一律发基础名 `minimax-h3`**（中转官方口径均如此，平台后缀 id 仅用于能力/价表/路由区分）。查询共用现有 queryBase（官方三 task_type 共享 Query 端点） | P0 |
| HHX-5 | Context-IR 请求/结果 | 建任务 body：`{model, content[], duration, ratio}`（**无 resolution**）；成功解析 `task.content.prompt` → 新字段 `resultText`，`usage.prompt_tokens/completion_tokens` → 新字段 `usageInputTokens/usageOutputTokens`（`total_tokens` 仍进 usageTokens 兜底） | P0 |
| HHX-6 | 再生成请求 | `MediaGenRequest` 加 `sourceTaskId` 字段；建任务 body `{model, source_task_id, resolution:"2K"}`（固定 2K，官方唯一值）；`aigc_watermark` 不传（默认 false） | P0 |
| HHX-7 | 能力层三模型分档 | `MediaModelCapabilityService` happyhorse 前缀默认改按后缀：t2v→`maxImages:0/maxAttachments:0/supportedRatios:HH官方9值/supportedResolutions:["720p","1080p"]`；i2v→现状 1 图 + `supportedRatios:[]`（空=前端隐藏比例控件，P2 核对前端空列表语义）+ `["480p","720p","1080p"]`；r2v→`maxImages:9/maxAttachments:9` + 官方 9 值 + `["720p","1080p"]`。新常量 `HH_RATIOS = ["16:9","9:16","1:1","4:3","3:4","4:5","5:4","9:21","21:9"]`。minimax 前缀加 `-regeneration` 后缀分支：全 0 输入、`supportedRatios:[]`、`["2k"]`；`-context-ir` 沿用 minimax 默认（输入限制同生成，正确）。config capabilities 覆盖机制不变（双保险） | P0 |
| HHX-8 | 价表种子行（V166） | 6 行，providerId 绑对应 provider 行（子查询取 id）：① `minimax-h3` VIDEO/SECOND `{"768p":0.5,"2k":0.8}` hasReference=false 通用行；② `minimax-h3-regeneration` VIDEO/SECOND `{"2k":0.3}`；③ `minimax-h3-context-ir` **kind=CHAT** `priceInputPerMillion=5.8, priceOutputPerMillion=23`（走 textCost input/output 分价；注意该行会出现在聊天价表页，模型名自解释，可接受）；④ `happyhorse-1.1-t2v` VIDEO/SECOND `{"720p":0.9,"1080p":1.2}`；⑤ `happyhorse-1.1-i2v` `{"480p":0.45,"720p":0.9,"1080p":1.2}`；⑥ `happyhorse-1.1-r2v` `{"720p":0.9,"1080p":1.2}`。全部幂等（NOT EXISTS 判重），回滚 SQL 记档头注释（V165 惯例） | P0 |
| HHX-9 | 提交/结算计费分流 | `MediaGenTaskService`：① Context-IR 提交估价走 CHAT 口径——预估 input = prompt 字符数 × 0.75（中英混合经验系数）+ 附件折算（图 1500 tok/张、视频 300 tok/秒、音频 60 tok/秒，粗估量级即可），预估 output = 4000 tok（官方示例 3426，取整上浮），`computeCost(CHAT, in, out)` 折价，fail-closed 拒单逻辑复用；真实扣费按 `usageInputTokens/usageOutputTokens` 同公式。② 再生成估价 = 源任务行 duration × 2k 秒价（提交时校验源任务存在/属主/succeeded/7 天内，查 `media_gen_tasks`）；真实扣费 `usage.total_seconds` × 2k 槽。③ 生成/三 HappyHorse 走现有 SECOND 链路零改动 | P0 |
| HHX-10 | Context-IR 结果落地 | 增强文本结果作为 `.md` 文件落现有文件服务（`stored_files`），任务行复用现有 result 文件链路（前端文本预览 + 下载）；不新增结果列 | P1 |
| HHX-11 | 前端入口 | ① 模型下拉自动带出（models 列表联动现状）。② 视频生成页：选 `minimax-h3-regeneration` 时表单切换为"源任务选择"模式（下拉本人近期 succeeded 的 minimax-h3 生成任务，回填 sourceTaskId，隐藏 prompt/附件/比例/时长控件，分辨率锁 2K）。③ 选 `minimax-h3-context-ir` 时正常表单（prompt + 多模态附件 + duration + ratio，无分辨率控件）。④ HappyHorse 三模型按能力自动显隐控件（i2v 无比例、t2v 无图片上传——能力层空列表/0 上限已驱动，P2 核对前端渲染） | P1 |

## 5. 数据模型（V166__seed_minimax_h3_happyhorse_providers.sql）

```sql
-- 幂等种子：两 provider 行 + 六价表行。回滚 SQL 记头注释（V165 惯例）。
-- provider 行（name 唯一判重）；api_key_enc 空 = 待管理员填 key。
INSERT INTO llm_providers (name, display_name, category, protocol, api_endpoint, api_key_enc, models, config, ...)
SELECT 'happyhorse', 'HappyHorse 1.1（ai.ctaigw.cn 中转）', 'VIDEO', 'dashscope',
       'https://ai.ctaigw.cn/v1/services/aigc/video-generation/video-synthesis', '',
       '["happyhorse-1.1-t2v","happyhorse-1.1-i2v","happyhorse-1.1-r2v"]',
       '{"queryEndpoint":"https://ai.ctaigw.cn/v1/tasks"}', ...
WHERE NOT EXISTS (SELECT 1 FROM llm_providers WHERE name = 'happyhorse');
-- 同款第二条 name='minimax-h3' protocol='minimax'
-- 价表 6 行：INSERT ... SELECT (SELECT id FROM llm_providers WHERE name='...') WHERE NOT EXISTS (同 provider+model+kind 判重)
```

（实体列名/审计填充列以 `llm_providers`、`pricing_rule` 实际 DDL 为准，P2 写迁移时对表核对；`created_by` 用系统管理员 id。）

**无 schema 变更**：本特性不加列不改表——provider config JSON 与价表行内 JSONB 槽承载全部新配置（MVR 系已验证的范式）。

## 6. 非功能需求

- **性能**：零新轮询面（任务型现状复用）；端点路由是字符串后缀判断；Context-IR 文本结果 KB 级，文件服务现状可承载。
- **安全**：① 种子行 apiKeyEnc **空**（用户"仅留 key 我自己填"），密钥 AES 加密 + 仅进 Authorization header 不落日志（三适配器既有范式）；② Context-IR 结果含用户 prompt 派生内容，随任务属主权限走（文件服务现状）；③ 脱敏快照：t2v 无媒体、r2v 多图逐项脱敏（redactedMediaUrl 循环已覆盖）、context_ir content[] 同 minimax 快照范式；④ 再生成校验源任务属主（不能拿别人任务白嫖超分）。
- **可回滚**：V166 种子幂等可重跑；回滚 = 按头注释 DELETE 种子行 + provider 两行；代码改动纯增量分支，revert 即回 i2v/生成现状。

## 7. 测试策略

- **单测**（后端，package-private 直测）：① Dashscope `buildCreateBody`：t2v 无 media + ratio 默认 16:9、有图 fail-fast；i2v 回归（1 张 first_frame 无 ratio）；r2v 1/9 张 reference_image + ratio、10 张 fail-fast；resolution 分模型回落（t2v 480p→720P）。② Minimax `buildCreateBody`：context_ir 无 resolution、regeneration 带 sourceTaskId+固定 2K、生成回归；端点推导（`-context-ir`→`/h3_context_ir`、`-regeneration`→`/video_regeneration`、config 覆盖优先、无后缀走原 endpoint）。③ `parseQueryResult`：context_ir 取 prompt+双 token、regeneration 取 url+seconds、失败话术。④ 能力：happyhorse 三档 + minimax regeneration 档 + HH_RATIOS 9 值。⑤ 计价：context_ir CHAT 公式（5.8/23）、估价预估 token 公式、happyhorse 三行分档取槽、regeneration 2k 槽。⑥ WireMock：中转三端点建任务/查态全流程（含 X-DashScope-Async 头断言）。
- **集成**：V166 连跑两遍幂等；迁移后 `GET /api/media/models` 六模型能力正确；估价 fail-closed（删价行 → 提交拒）。
- **人工测试标记**（真 key 真中转，需用户配合）：六模型各真跑一条——t2v 720P、i2v 480P、r2v 720P 多图、minimax 生成 2K、context_ir 纯文（核 resultText 完整性 + token 计费对账）、regeneration（从生成任务发起）；预估 vs 实扣 vs 中转账单三方对账；24h 后查任务态（URL 失效表现）。
- **回归**：seedance/ark 全链不变；happyhorse 无后缀旧 id 仍走 i2v；minimax 生成（无后缀）走原端点；现有价表/能力不受种子影响。

**风险登记**：① 中转对 `happyhorse-1.1-t2v/i2v` 模型 id 及 reference_image 类型的实际支持未实测（无 key）——人工验收确认，若中转只认部分 id 则 models 列表相应裁剪；② 中转 24h/7d 查询有效期未标注——按官方 24h（happyhorse）/7d（minimax）口径；③ 输入侧费用不建模导致平台扣费 < 中转账单（§8）。

## 8. 边界与不做

- **输入侧费用不建模**：minimax 输入视频 ¥0.5-0.8/秒、输入图 ¥0.2/张、再生成输入费——SECOND 链只算输出秒。后续要精确可加 hasReference 维度第二行，本期不做（与 seedance 系"含/不含视频输入"双行口径的区别：那是 token 估算系数，这是实收输入费，等中转账单量级确认后再决定是否补）。
- **不做** Context-IR → 生成的自动串联（官方 Full 2K-Workflow 三步流）：用户手动复制增强 prompt 到生成表单。
- **不接** `callback_url` 回调（轮询制现状）；**不透传** seed（无控件）；watermark 平台口径维持 null=false 不加（官方默认 true，语义差异记档——与 i2v 现状一致）。
- **不接** HappyHorse 1.0 系模型（id 入 models 列表即自动可用，用户自行决定）；**不接** regeneration 的 `content base_video` 第二形态（只走 source_task_id——平台源任务天然在库）。
- 官方国际站 paygo 页无 H3 人民币价目：生成价按国内站口径、附属两端点价按中转文档（§2 已注来源）。
- Context-IR 64MB 请求体上限：平台 data URI 沿用，超限由网关拒（官方建议大文件走公网 URL，平台附件已是 URL/data URI 双轨）。

## 9. 涉及文件清单（P2 落点索引）

| 文件 | 改动 |
|---|---|
| `backend/.../db/migration/V166__seed_minimax_h3_happyhorse_providers.sql` | 新增（种子） |
| `backend/.../media/provider/DashscopeVideoProvider.java` | buildCreateBody 三形态分支 |
| `backend/.../media/provider/MinimaxVideoProvider.java` | 附属端点路由 + 后缀剥除 + context_ir/regeneration body 与解析 |
| `backend/.../media/config/MediaModelCapabilityService.java` | happyhorse 三档 + minimax regeneration 档 + HH_RATIOS |
| `backend/.../media/dto/MediaGenRequest.java` | + sourceTaskId |
| `backend/.../media/dto/MediaGenResult.java` | + resultText / usageInputTokens / usageOutputTokens |
| `backend/.../media/service/MediaGenTaskService.java`（含 Worker 协同） | 提交分流（context_ir CHAT 估价 / regeneration 源任务校验）+ 结算分流 + resultText 落文件 |
| `frontend/src/...`（视频生成页组件） | regeneration 源任务选择模式 + context_ir 表单适配 + 能力驱动控件显隐核对 |
| 对应单测文件 ×4-5 | 新增/扩展 |

## 10. 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| 中转（relay/gateway） | 第三方代理站，转发请求到官方 API，用自己的域名和计费 | 官方 `api.minimax.io`，中转 `ai.ctaigw.cn`——参数格式一样，换域名和 key 就能用 |
| provider 适配器（MediaGenProvider） | 平台里对接某家视频厂商协议的翻译器代码 | `DashscopeVideoProvider` 把平台的统一请求翻译成百炼的 input/parameters 格式 |
| 协议（llm_providers.protocol） | 数据库里标"这条 provider 行用哪个翻译器"的字段，值=适配器 ID | protocol=`minimax` → 请求路由到 MinimaxVideoProvider 处理 |
| t2v / i2v / r2v | 文生视频 / 图生视频 / 多图参考生视频 | 给 1 张猫的图让它动起来=i2v；给 9 张猫图让它参考着生成=r2v |
| Context-IR（H3 上下文理解） | 先把你的图文音频素材"读懂"并改写成一条结构化的高质量视频提示词，不出视频 | 输入"女舰长站在观景窗前"+素材 → 输出带镜头编号/音效/配乐描述的长 prompt，再拿去生成 |
| 再生成（regeneration） | 把已生成的 768P 视频超分放大成 2K，不用重新生成 | 生成任务完成后点"再生成"，传源任务 id，出 2K 版 |
| SECOND 计费 / 分档秒价槽 | 按视频秒数收费；不同分辨率每秒单价不同，存在价表行的一个 JSON 字段里 | `{"768p":0.5,"2k":0.8}` = 768P 每秒 5 毛钱 |
| 千 tokens 价 / 每百万价 | 文本计量单位价，1 百万 tokens=1M；千 tokens 价×1000=每百万价 | ¥0.0058/千 tokens = ¥5.8/百万 tokens |
| 幂等种子（Flyway seed） | 数据库迁移脚本里预置的数据，重复执行不会插重复行 | 迁移跑两遍，provider 表里也只有一行 happyhorse |
| X-DashScope-Async | 百炼协议要求的请求头，声明"这是异步任务" | 缺了它百炼报错"不支持同步调用" |
| fail-closed 估价 | 算不出价格就拒绝提交，宁可拒单不可白嫖 | 价表没配 → 提交直接报 PRICING_NOT_FOUND |

---

## 术语行内批注（首次出现补注）

- **中转**（第三方代理站，转发请求到官方 API 并按自己的价结算）——§标题。
- **t2v/i2v/r2v**（文生视频/图生视频/多图参考生视频三种模式）——§2.1。
- **Context-IR**（先把素材读懂改写成结构化提示词的任务，只出文本不出视频）——§2.2。
- **再生成**（把已生成的 768P 视频超分成 2K 的任务）——§2.2。
- **SECOND 分档秒价槽**（按秒计费时，各分辨率每秒单价存价表行的 JSONB 字段）——§1。
- **fail-closed**（估价失败即拒单，防止无价白嫖）——§1。

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-30 | 建立规格（HHX-1~11，Q1~4 拍板，官网对照表） | 用户经 ai.ctaigw.cn 中转接入 MiniMax H3（含 Context-IR/再生成）与 HappyHorse 1.1 三模型，要求参数计费对齐官网、仅填 key 即用 |
