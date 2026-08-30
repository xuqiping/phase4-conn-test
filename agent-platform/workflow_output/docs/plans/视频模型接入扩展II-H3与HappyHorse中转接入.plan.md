# Plan · 视频模型接入扩展 II（MiniMax H3 全家桶 + HappyHorse 1.1 三模型，ai.ctaigw.cn 中转）

> Phase 2 实现计划。规格真相源：[视频模型接入扩展II-H3与HappyHorse中转接入.md](../specs/视频模型接入扩展II-H3与HappyHorse中转接入.md)（HHX-1~11）。
> 与规格冲突时：改实现或回改规格并注明原因。
> **硬闸门：本计划经用户明确许可后才进 P3 实现。**

## 实现事实锚点（P2 探查，P3 直接引用）

- `llm_providers` 列（V6+V10+V23）：`name(UNIQUE)/display_name/api_endpoint/api_key_enc/models(JSONB)/config(JSONB)/status/sort_order/category/protocol/审计列/deleted/version`。
- `pricing_rule` 列（V66+V95+V152/153/162/164）：`kind/provider_id/model/price_input_per_million/price_output_per_million/video_billing_mode/price_per_second/price_per_image/resolution/est_per_resolution/token_price_per_resolution/price_per_second_per_resolution/has_reference/effective_from`。
- `media_gen_tasks.task_type` VARCHAR(16) **无 CHECK**——`CONTEXT_IR`/`REGENERATION` 新值零迁移可用（≤16 字符 ✓）。
- 提交估价链：[MediaGenTaskService.java:248](../../../backend/src/main/java/com/superprogrammer/media/service/MediaGenTaskService.java#L248) `estimateVideoPoints` → [PricingService.estimateVideoYuan](../../../backend/src/main/java/com/superprogrammer/billing/service/PricingService.java)；fail-closed 硬闸 :253-256。
- 结算链：[MediaGenTaskWorker.java:281](../../../backend/src/main/java/com/superprogrammer/media/service/MediaGenTaskWorker.java#L281) `handleSucceeded`——**先查 resultUrl 空→markDownloadFailed（:284-288，Context-IR 分支必须在此之前）**→ `settleMediaSuccess`（[MediaBillingService.java:231](../../../backend/src/main/java/com/superprogrammer/billing/service/MediaBillingService.java#L231)，**tokensOutput 硬编码 null :244**——CHAT 分价要新重载）→ `markSucceeded`。
- 前端锚点：[api/media.ts:406](../../../frontend/src/api/media.ts#L406) `submitVideo` / [:443](../../../frontend/src/api/media.ts#L443) `estimatePreview`；[VideoGenView.vue:965](../../../frontend/src/views/VideoGenView.vue#L965) 提交 / [:1005](../../../frontend/src/views/VideoGenView.vue#L1005) 估价；画布节点 [PropertyPanel.vue:1120](../../../frontend/src/components/canvas/PropertyPanel.vue#L1120) 也走 `estimatePreview`。

## Chunk 划分（8 块，依赖序即实施序）

### Chunk A · V166 种子迁移（HHX-1/2/8）

- **目标**：发版即就位两 provider + 六价表行，key 留空。
- **动作**（伪代码）：
  ```
  新文件 V166__seed_minimax_h3_happyhorse_providers.sql：
    头注释：用途 + 回滚 SQL（DELETE pricing_rule WHERE model IN (六id) AND provider_id IN (两name子查询)；DELETE llm_providers WHERE name IN ('minimax-h3','happyhorse')）
    INSERT llm_providers (name,display_name,category,protocol,api_endpoint,api_key_enc,models,config,status,created_by)
      VALUES ('happyhorse','HappyHorse 1.1（ai.ctaigw.cn 中转）','VIDEO','dashscope',
              'https://ai.ctaigw.cn/v1/services/aigc/video-generation/video-synthesis','',
              '["happyhorse-1.1-t2v","happyhorse-1.1-i2v","happyhorse-1.1-r2v"]',
              '{"queryEndpoint":"https://ai.ctaigw.cn/v1/tasks"}','ACTIVE', 1)
      WHERE NOT EXISTS (name='happyhorse')
    INSERT llm_providers ... ('minimax-h3','MiniMax H3（ai.ctaigw.cn 中转）','VIDEO','minimax',
              'https://ai.ctaigw.cn/v1/video_generation','',
              '["minimax-h3","minimax-h3-context-ir","minimax-h3-regeneration"]',
              '{"queryEndpoint":"https://ai.ctaigw.cn/v1/query/video_generation"}','ACTIVE', 1)
      WHERE NOT EXISTS (name='minimax-h3')
    六条 INSERT pricing_rule (kind,provider_id,model,video_billing_mode,price_per_second,price_per_second_per_resolution,price_input_per_million,price_output_per_million,has_reference)
      provider_id = (SELECT id FROM llm_providers WHERE name=对应 AND deleted=0)   -- 子查询
      ① ('VIDEO',·,'minimax-h3','SECOND',0.5,'{"768p":0.5,"2k":0.8}',NULL,NULL,FALSE)
      ② ('VIDEO',·,'minimax-h3-regeneration','SECOND',0.3,'{"2k":0.3}',NULL,NULL,FALSE)
      ③ ('CHAT', ·,'minimax-h3-context-ir',NULL,NULL,NULL,5.8,23,FALSE)
      ④ ('VIDEO',·,'happyhorse-1.1-t2v','SECOND',0.9,'{"720p":0.9,"1080p":1.2}',NULL,NULL,FALSE)
      ⑤ ('VIDEO',·,'happyhorse-1.1-i2v','SECOND',0.9,'{"480p":0.45,"720p":0.9,"1080p":1.2}',NULL,NULL,FALSE)
      ⑥ ('VIDEO',·,'happyhorse-1.1-r2v','SECOND',0.9,'{"720p":0.9,"1080p":1.2}',NULL,NULL,FALSE)
      每条 WHERE NOT EXISTS (SELECT 1 FROM pricing_rule WHERE kind=· AND model=· AND provider_id=·子查询)
  ```
- **涉及文件**：`V166__*.sql`（新，1 个）
- **依赖**：无（首发）
- **验证**：本地起后端 Flyway 自动跑（或 psql 手动 `flyway repair` 前提下跑两遍验幂等）；`SELECT` 核对：两 provider 行 models/config 正确、六价表行槽位 JSON 逐字节对照规格 §4 HHX-8；`GET /api/media/models`（admin token）见 6 新模型、能力档正确。

### Chunk B · DTO 字段 + 能力层分档（HHX-5/6/7）

- **目标**：平台侧表达三形态能力与两新任务的出入参。
- **动作**：
  ```
  MediaGenRequest：+String sourceTaskId（再生成源任务，仅 regeneration 用）
  MediaGenResult：+String resultText（Context-IR 增强提示词）
                  +Long usageInputTokens / usageOutputTokens（CHAT 计费双 token；usageTokens 仍存 total 兜底）
  MediaModelCapabilityService：
    +常量 HH_RATIOS = ["16:9","9:16","1:1","4:3","3:4","4:5","5:4","9:21","21:9"]   // 官方 9 值，无 adaptive
    defaultsFor() happyhorse 分支内部再按后缀：
      含 "-t2v" → maxImages 0/maxVideos 0/maxAudios 0/maxAttachments 0/ratios HH_RATIOS/res ["720p","1080p"]/3-15s/audio false
      含 "-r2v" → maxImages 9/其余 0/maxAttachments 9/ratios HH_RATIOS/res ["720p","1080p"]/3-15s/audio false
      否则（i2v 或旧 id）→ 现状值但 supportedRatios=List.of()（空=前端隐藏比例控件）+res ["480p","720p","1080p"]
    defaultsFor() minimax 分支内部：
      含 "-regeneration" → 全 0 输入/maxAttachments 0/ratios 空列表/res ["2k"]/4-15s/audio false
      含 "-context-ir" → 沿用 minimax 默认（9图/3视频/3音频/15总/768p+2k/4-15s）
  ```
- **涉及文件**：`MediaGenRequest.java`、`MediaGenResult.java`、`MediaModelCapabilityService.java` + 对应测试（3+1）
- **依赖**：无
- **验证**：`mvn -pl backend test -Dtest=MediaModelCapabilityServiceTest`（新增 t2v/i2v/r2v/regeneration/context-ir 五断言组）；`mvn compile` 过。

### Chunk C · Dashscope 适配器三形态（HHX-3）

- **目标**：happyhorse t2v/r2v 请求体正确发给中转。
- **动作**：
  ```
  DashscopeVideoProvider.buildCreateBody 按模型 id 小写后缀分支：
    "-t2v"：若附件含图/视频/音频 → fail-fast("t2v 不支持参考媒体")
            parameters = {resolution(映射，480p→720P 回落), duration(clamp 3-15 默认 5), ratio(req.ratio 空则 "16:9")}
            input = {prompt}（media 不出现）
    "-r2v"：收集全部 image 附件（frameRole 忽略——r2v 无首尾帧语义）
            0 张 → fail-fast；>9 张 → fail-fast
            media = 附件.map(a -> {type:"reference_image", url:a.url})
            parameters 同 t2v（ratio 必发）
    其他（i2v/旧 id）：现状不动（1 张 first_frame、无 ratio）
  resolution 映射沿用 RESOLUTION_OUT；t2v/r2v 的 480p 输入落 720P（能力层已挡 480p，双保险）
  ```
- **涉及文件**：`DashscopeVideoProvider.java` + `DashscopeVideoProviderTest`（1+1）
- **依赖**：Chunk B（MediaGenResult 不涉及，仅请求侧——实际无依赖，可与 B 并行）
- **验证**：单测断言三形态 body JSON（t2v 无 media 键+ratio 默认；r2v 1/9 张 reference_image+10 张抛；i2v 回归不变）；WireMock 建/查全流程含 `X-DashScope-Async: enable` 头。

### Chunk D · Minimax 附属端点路由与解析（HHX-4/5）

- **目标**：context-ir/regeneration 两端点复用 minimax 适配器；结果正确回填。
- **动作**：
  ```
  MinimaxVideoProvider：
    +私有 stripSuffix(model) = model 去掉 "-context-ir"/"-regeneration" 尾巴（发中转的 body.model 用基础名）
    端点路由（建任务 URL）：
      config.contextIrEndpoint 非空且 model 含 "-context-ir" → 用之
      config.regenerationEndpoint 非空且 model 含 "-regeneration" → 用之
      否则按后缀：apiEndpoint 去尾 "/video_generation" + "/h3_context_ir" 或 "/video_regeneration"
      （strip 前先去尾斜杠；无后缀 → 原 apiEndpoint，现状零改动）
    buildCreateBody 分支：
      context-ir → {model:基础名, content[](现状复用), duration(clamp 4-15), ratio(默认 "16:9")}   // 无 resolution
      regeneration → {model:基础名, source_task_id:req.sourceTaskId(空则 fail-fast), resolution:"2K"}  // 无 content/duration/ratio
    parseQueryResult 分支（按请求侧记的形态，或按 task_type 字段——实现取 provider 内部调用时已知 model 的方式，P3 定：queryTask 需能拿到 model——现有签名 queryTask(arkTaskId, providerId) 拿不到，改法：worker 已有 task.getModel()，透传为 queryTask(id, providerId, model) 重载）：
      context-ir 成功 → resultText = task.content.prompt；usageInputTokens = usage.prompt_tokens
                        usageOutputTokens = usage.completion_tokens；usageTokens = usage.total_tokens；resultUrl 不设
      其余 → 现状（resultUrl = task.content.url；usageTokens = usage.total_seconds）
    status 映射共用现状
  ```
- **涉及文件**：`MinimaxVideoProvider.java`、`MediaGenProvider.java`（接口若加 queryTask 重载默认方法）、`MediaGenTaskWorker.java`（调用点改传 model，一行）+ `MinimaxVideoProviderTest`（3+1）
- **依赖**：Chunk B（resultText/双 token 字段）
- **验证**：单测：端点推导三态（后缀/config 覆盖/无后缀）；context-ir body 无 resolution；regeneration body 带 source_task_id+固定 2K；parseQueryResult context-ir 取 prompt+双 token、regeneration 取 url+seconds。

### Chunk E · 提交分流（HHX-9 上半 + Controller）

- **目标**：两新任务过校验、过 fail-closed 估价、落正确 task_type。
- **动作**：
  ```
  MediaController 提交端点：+@RequestParam sourceTaskId（可空）透传 service
  MediaGenTaskService.submit 分支（按 resolvedModel 后缀）：
    含 "-context-ir"：
      task_type = "CONTEXT_IR"；prompt 非空校验（官方 content 必含非空 text）
      附件校验复用现状（minimax 能力 9图/3视频/3音频/15总）
      config 落 {prompt,ratio,duration,resolution:null,attachments}
      估价 estimateContextIrPoints(providerId, model, prompt, attachments)：
        estIn = prompt.length*0.75 + 图*1500 + 视频秒*300 + 音频秒*60（附件秒数取不到就按 0——est 只要不小于真实量级即可，宁可高估）
        estOut = 4000
        yuan = pricingService.computeCost(CHAT, providerId, model, estIn, estOut, ...)
        fail-closed 硬闸复用（est<=0 拒）
    含 "-regeneration"：
      源任务校验（不满足即 400）：
        sourceTaskId 非空；media_gen_tasks 行存在
        task.userId == 提交者（或 admin 旁路）
        task.status == SUCCEEDED
        task.providerId == provider.getId() 且 task.model == "minimax-h3"（基础生成模型，非再生成套娃）
        task.createdAt >= now-7d
      duration = 源任务 request_config.duration（取不到默认 5）
      task_type = "REGENERATION"；config 落 {sourceTaskId, duration, resolution:"2k"}
      附件/prompt/ratio 全部忽略（前端也不给）
      估价 = estimateVideoPoints(providerId, model, duration, false, "2k")   // SECOND 槽 {"2k":0.3}，现状零改动
    无后缀 → 现状零改动
  estimatePreview：同后缀分流（context-ir 用同公式；regeneration 需要 sourceTaskId 参数或前端传 duration——P3 取：preview 接口 +sourceTaskId 可选，有则按源任务 duration 估）
  ```
- **涉及文件**：`MediaController.java`（或 media 包控制器实际名）、`MediaGenTaskService.java` + 测试（2+1）
- **依赖**：Chunk A（价表行在库，估价才能算出）、Chunk B（能力分档）
- **验证**：单测：context-ir 空 prompt 拒；regeneration 源任务四类非法（不存在/他人/未成功/超 7 天）各拒；估价公式数值断言（如 prompt 100 字 → estIn=75；总 yuan=(75*5.8+4000*23)/1M≈0.0921）；集成：迁移后库上提交六模型各一单（mock 中转）过闸。

### Chunk F · 结算分流 + Context-IR 结果落地（HHX-9 下半/10）

- **目标**：Context-IR 按 CHAT 双 token 实扣、文本落文件；regeneration/生成零改动。
- **动作**：
  ```
  MediaBillingService：
    +重载 settleMediaSuccess(..., Integer tokensInput, Integer tokensOutput, ...)：
      computeCost(kind, providerId, model, tokensInput, tokensOutput, ...)   // 原 11 参版委托新 12 参版传 tokensOutput=null，旧调用点零改动
  MediaStorageService（或 FileStorageService）：
    +storeText(text, userId, hint)：写 .md 进 stored_files(source=MEDIA) 返回 fileId（复用现有存储路径；不放公网）
  MediaGenTaskWorker.handleSucceeded 开头分支：
    if (task.taskType == "CONTEXT_IR")：
      resultText 空 → markDownloadFailed("context-ir 无结果文本")
      fileId = storeText(result.getResultText(), userId, "task-"+taskId)
      yuan 积分 = settleMediaSuccess(..., KIND_CHAT, tokensInput=result.usageInputTokens, tokensOutput=result.usageOutputTokens, videoSeconds=0, ...)
      失败闸/退款/markSucceeded 复用现状结构（抽出公共尾巴或内联复制——P3 取内联，避免大重构）
    else 现状（videoUrl 判空 → 下载 → SECOND 结算）
  resolveUsage/markSucceeded 的 tokensCost = usageTokens(total)——CHAT 行不消费它，仅落库展示
  ```
- **涉及文件**：`MediaBillingService.java`、`MediaStorageService.java`、`MediaGenTaskWorker.java` + worker 测试（3+1）
- **依赖**：Chunk B（resultText/双 token）、Chunk D（解析侧先有值）
- **验证**：单测：context-ir 成功→storeText 调用+CHAT 结算金额断言（in 75/out 3426 → ¥0.0828 量级）；结算失败→退款+FAILED；regeneration 成功走 SECOND {"2k":0.3}×duration。

### Chunk G · 前端（HHX-11）

- **目标**：两新模型可用表单正确；能力驱动控件显隐。
- **动作**：
  ```
  api/media.ts：MediaSubmitRequest +sourceTaskId?；estimatePreview 参数 +sourceTaskId?
  VideoGenView.vue：
    模型选中 → 从 /api/media/models 能力驱动（已有机制核对）：
      maxImages==0 且 maxAttachments==0 → 隐藏素材上传区（t2v/regeneration）
      supportedRatios 为空数组 → 隐藏比例下拉（i2v/regeneration）
      supportedResolutions 含单值 "2k" → 分辨率锁 2K 禁选
    model == "minimax-h3-regeneration" → 切"源任务"模式：
      隐藏 prompt/素材/比例/时长/分辨率；显示源任务下拉（拉本人 succeeded minimax-h3 任务列表——复用现有历史列表 API 过滤 model+status；列表项显示 任务id+时长+创建时间）
      未选源任务 → 提交禁用；提交体 {model, sourceTaskId}
    model == "minimax-h3-context-ir" → 正常表单但隐藏分辨率（官方无此参数）；估价 preview 走新参数
    任务历史：task_type CONTEXT_IR 的行 → 结果区显示文本预览（result file .md 内容或 resultText 接口——P3 取：历史详情拉文件内容接口现状有无，无则列行显示"提示词增强文本"标签+下载按钮）
  PropertyPanel.vue（画布视频节点）：模型下拉自动带出新模型；能力显隐同一套（联动点清单 #1——两处下拉必须同逻辑，抽 composable 或复制时同步）
  ```
- **涉及文件**：`api/media.ts`、`views/VideoGenView.vue`、`components/canvas/PropertyPanel.vue` + `VideoGenView.test.ts`（3+1）
- **依赖**：Chunk E（提交参数）、Chunk A（模型列表）
- **验证**：`npm run test`（VideoGenView 既有测试扩：regeneration 模式显隐断言）；人工：六模型表单各看一眼 + t2v 提交一单。

### Chunk H · 集成验证 + 文档回填

- **目标**：全链绿 + 文档不漂移。
- **动作**：
  ```
  后端：mvn test 全量（含既有 seedance/minimax/dashscope 回归）
  前端：npm run test + npm run build
  本地端到端：起前后端 → admin 登录 → 设置页看两 provider 行（key 空）→ 手填 key（用户动作）
    → 视频页六模型各提交（真中转，需用户 key）→ 历史出结果 → 价表页看六新行
  文档：3x. 模型接入问题.md「未解决」节回填（已接入+配好+key 待填+人工验收步骤）
        feature-map 若有媒体/provider 速查表 → 补两行
        user-ops：管理员填 key 步骤 + 输入侧费对账差异说明（规格 §8 口径）
  ```
- **涉及文件**：`人工测试问题/3x. 模型接入问题.md`、feature-map/user-ops 对应文件（≤3）
- **依赖**：A-G 全部
- **验证**：人工测试标记清单（规格 §7）逐项打勾或记问题。

## 技术坑点预判（P3 逐条对照）

| # | 坑 | 场景 | 规避 |
|---|---|---|---|
| 1 | Dashscope 查询地址推导错 | 中转 URL 无 `/api/v1/` 段 → 回落 `host+/api/v1/tasks` ≠ 中转 `/v1/tasks` | V166 config 显式 `queryEndpoint`（Chunk A）；单测加"config 缺 queryEndpoint 时打错地址"的防护断言不做——靠种子必配 |
| 2 | Minimax 端点拼接 | endpoint 尾带 `/` 或路径大小写漂移 → strip 失败拼出畸形 URL | strip 前去尾斜杠 + `endsWith("/video_generation")` 判定，不满足直接用 config 覆盖或抛清晰错误 |
| 3 | Context-IR 无 resultUrl 被 worker 误杀 | `handleSucceeded` 第一行就判 resultUrl 空 → markDownloadFailed | CONTEXT_IR 分支放最前（Chunk F）；task_type 判定先于 URL 判定 |
| 4 | CHAT 结算 output 侧恒 0 | `settleMediaSuccess` 内 `computeCost(..., tokensInput, null, ...)` 硬编码 | 新 12 参重载传 tokensOutput；旧 11 参版委托传 null，旧调用点零改动 |
| 5 | 估价 0 撞 fail-closed | context-ir 空 prompt 无附件 → estIn≈0（estOut=4000 恒>0 实际不会 0；但 computeCost CHAT 价行缺时抛 PRICING_NOT_FOUND） | 空 prompt 提交侧先拒（官方要求非空 text）；价行缺 → 硬闸语义正确（管理员补价） |
| 6 | 模型 id 逐字节不一致 | 价表行 `model` 与 `llm_providers.models[]` 差一字符（如大小写、连字符）→ resolveRule 永不命中 → 全部拒单 | V166 两组 id 字符串从规格 §4 复制；Chunk A 验证步骤 `SELECT` 逐字节核对 + 提交冒烟即暴露 |
| 7 | 前端空比例数组渲染空下拉 | Naive UI select options=[] 仍渲染控件 | `v-if="ratios.length"` 隐藏（Chunk G）；VideoGenView 测试断言 i2v 无比例控件 |
| 8 | queryTask 拿不到 model | 现签名 `queryTask(arkTaskId, providerId)` 无法判 task_type → context-ir 解析分支进不去 | 接口加 `queryTask(id, providerId, model)` 重载（默认方法保持旧签名兼容），worker 传 `task.getModel()` |
| 9 | 7 天窗口时区 | `created_at TIMESTAMPTZ` 与 `now()` 比较无坑 | 直接 `task.createdAt.isAfter(now.minusDays(7))` |
| 10 | 并发重复再生成 | 同源任务被并发提交两次再生成 | 无害（中转侧自行限），平台不加锁——记边界，非坑 |
| 11 | 附件秒数取不到 | estIn 公式视频/音频项需要时长，附件表无秒数 | estIn 按图张数+prompt 为主，视频/音频项取不到记 0——est 只需不低于真实量级（output 侧 4000 恒在，闸必过） |
| 12 | CHAT 价行出现在聊天价表页 | `minimax-h3-context-ir` kind=CHAT → admin 价表页聊天下拉多一项 | 可接受（模型名自解释），规格 §4 HHX-8 已注明；不做过滤 |

## 安全检查清单（对照规格 §6）

| 项 | 覆盖点 | 验证方式 |
|---|---|---|
| 密钥不落日志 | 种子 apiKeyEnc 空；适配器仅 Authorization header（现状范式，本计划零新增打印点） | grep 新代码无 api_key/key 日志输出 |
| sourceTaskId 越权 | 提交校验属主（或 admin 旁路同现状口径） | Chunk E 单测"他人任务拒" |
| 附件归属 | context-ir 复用 checkAttachmentOwnership | 现状代码路径，回归即证 |
| 输入校验 | duration/ratio/resolution 白名单走能力层；t2v 拒媒体；r2v 1-9 张 | Chunk B/C 单测 |
| 审计 | submit/success 行现状范式；context-ir 审计 detail 只记 tokens 与 model，不记 resultText 全文（防审计表膨胀+内容外泄面） | Chunk F 断言审计 detail 无 resultText |
| 权限 | media:gen 已 gate，两新任务同入口同权限，零新权限码 | 无新增——复核即可 |

## 功能联动点清单（正向必漏 bug，边界含反向/取消/批量）

| # | 触发动作 | 联动对象 | 预期变化 | 边界 |
|---|---|---|---|---|
| 1 | 模型下拉切到 happyhorse-1.1-t2v / -regeneration | 素材上传区（VideoGenView **和** 画布 PropertyPanel 两处） | maxImages=0 且 maxAttachments=0 → 上传区隐藏 | 切回 seedance/minimax-h3 → 恢复；切 i2v（maxImages=1）→ 恢复但单图；两处下拉逻辑必须同步，漏一处即画布节点露上传控件提交被 400 |
| 2 | 模型下拉切到 happyhorse-1.1-i2v / -regeneration | 比例下拉 | supportedRatios 空数组 → 隐藏 | 切 t2v/r2v → 恢复且选项=官方 9 值（无 adaptive）；已选过 4:5 再切 minimax-h3（有 adaptive 无 4:5）→ 须清空或回落 16:9，不能把不支持的值带进提交 |
| 3 | 模型下拉切到 minimax-h3-regeneration | 整表单 | 切"源任务选择"模式（prompt/素材/比例/时长/分辨率全隐，源任务下拉出现） | 未选源任务 → 提交按钮禁用；切回其他模型 → 表单还原（已填 prompt 不丢——暂存而非清空） |
| 4 | 源任务下拉选择某条 | 估价预览数字 | 按源任务 duration×2K 秒价实时刷新 | 清空选择 → 预览归零/禁用态；源任务列表只列 succeeded+7 天内+本人——过期任务不出现（而非出现后报错） |
| 5 | 任务成功进历史列表 | 结果预览组件 | CONTEXT_IR 行显示文本预览/下载（.md），非视频播放器 | 普通视频行零变化；文本超长 → 截断+展开；REGENERATION 行显示 2K 视频与普通行一致 |
| 6 | admin 在价表页看到新 CHAT 行 | 聊天价表下拉 | minimax-h3-context-ir 出现在聊天模型候选 | 不误删不误改（provider 专属行）；改价写新行走 effective_from 现状 |

## 运维考量清单（7 类逐条落字）

| 类 | 结论 | 落点 |
|---|---|---|
| 可观测性 | **做**（最小）：worker 成功日志补 resultText 长度/源任务 id 一行 | Chunk F |
| 配置开关 | **做**（零成本）：provider 行 status=INACTIVE 即停新任务（现成机制），不动代码 | 无代码，user-ops 记步骤 |
| 可回滚 | **做**：V166 头注释回滚 SQL；代码纯增量分支 revert 即回 | Chunk A |
| 限流/熔断/降级 | **不做**：中转挂→查询异常退避→超时 FAILED 现状链路已兜；WebClient 超时现状 | 无 |
| 运维入口 | **做**：管理员设置页填 key（现状页面）；DOWNLOAD_FAILED 重试入口现状 | Chunk H user-ops 步骤 |
| 告警阈值 | **后续再说**：现有 bizMetrics 媒体终态计数已够，中转账单对账差异无指标可埋（外部账本），先人工 | 无 |
| 容量/性能预案 | **后续再说**：任务表 append-only+文本结果 KB 级，现有归档策略覆盖；输入侧费建模待中转账单量级确认（规格 §8） | 无 |

## 验证汇总（每 Chunk 内含，此处总口径）

- 后端单测：`mvn -pl backend test`（新增 ≈5 测试类/≈30 断言组）
- 前端：`npm run test && npm run build`
- 集成：本地库迁移两遍幂等 + 六模型 mock 提交过闸
- 人工测试标记（需用户 key）：六模型真中转各一单 + 预估/实扣/中转账单三方对账 + r2v 用 reference_image 验网关认不认（规格风险②）

---

## 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| 伪代码（pseudocode） | 描述步骤的人话代码，实现时翻译成真代码 | "若模型带 -t2v 后缀 → 请求体不拼 media 键" |
| 重载（overload） | 同名方法多套参数签名，按传参自动选 | `settleMediaSuccess(...11参)` 与 `(...12参带 tokensOutput)` |
| fail-closed | 出错时选"拒绝"而不是"放行" | 估价算不出 → 拒提交，不让白嫖 |
| 回滚 SQL | 撤销一次迁移要执行的反向语句，记在迁移文件头注释 | `DELETE FROM llm_providers WHERE name IN (...)` |
| WireMock | 单测里假装是外部 API 的本地假服务器 | 假装 ai.ctaigw.cn 返回 task_id，测适配器拼包对不对 |
| 硬闸门（gate） | 没过关就不许进下一阶段 | 计划未经用户点头 → 不写实现代码 |
| append-only | 只许追加不许改删的表 | 任务日志表——错了也是事实，归档时再清 |

## 变更记录

| 日期 | 变更 | 原因 |
|---|---|---|
| 2026-08-30 | 建计划（Chunk A-H + 坑 12 条 + 联动 6 条 + 运维 7 类） | 规格 HHX-1~11 拆可执行块 |
