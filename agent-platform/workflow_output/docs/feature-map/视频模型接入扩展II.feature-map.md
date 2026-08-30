# 视频模型接入扩展II · Feature Map（功能-代码速查表）

> MiniMax H3 全家 + HappyHorse 1.1 三形态，经 `ai.ctaigw.cn` 中转接入；计价=中转价。
> 规格 `../specs/视频模型接入扩展II-H3与HappyHorse中转接入.md`，计划 `../plans/视频模型接入扩展II-H3与HappyHorse中转接入.plan.md`。

## 核心调用链

```
提交: MediaGenController.submit → MediaGenTaskService.doSubmit(附属分流/估价/预检)
  → INSERT media_gen_tasks(task_type=CONTEXT_IR|REGENERATION|...)
轮询: MediaGenTaskWorker.process → MinimaxVideoProvider|DashscopeVideoProvider(按 protocol)
  → query → parseQueryResult(context_ir 探测: task_type 含 context_ir 或 有 prompt 无 url)
  → SUCCEEDED: CONTEXT_IR → MediaStorageService.storeText(.md 落库)
              其他 → downloadAndStore(.mp4 落库)
结算: MediaBillingService.settleMediaSuccess(14 参 tokensOutput 版) → CHAT 双腿 | VIDEO 秒价
```

## 后端文件

| 文件 | 作用 | 大白话 |
|---|---|---|
| `db/migration/V166__*.sql` | 种子：2 provider 行（key 留空）+ 6 价表行 | 中转站柜台预登记：两个商家+明码标价牌，钥匙（key）留给店主自己塞 |
| `media/config/MediaModelCapabilityService.java` | 能力分档：happyhorse t2v/r2v/i2v 三形态 + minimax `-regeneration` 档 | 每个模型的「体检表」：能吃几张图、有哪些分辨率档。i2v 的 ratio 给空列表=前端自动藏掉比例下拉 |
| `media/provider/DashscopeVideoProvider.java` | t2v（纯文本 body）/ r2v（多图 reference_image）/ i2v（first_frame 恰 1 张）分支 | 阿里系快递单：t2v 只写地址，i2v 必须附一张首帧照片，r2v 可附最多 9 张 |
| `media/provider/MinimaxVideoProvider.java` | 附属端点推导路由 + context_ir 文本结果解析 + regeneration 极简 body | 同一模型的三个窗口：生成/增强/再生成。把 `/video_generation` 尾巴剪掉换 `/h3_context_ir` 即增强窗口；查态返回「有文字没视频」就按增强文本入库 |
| `media/dto/MediaSubmitRequest.java` | prompt 去 @NotBlank + `sourceTaskId` | 再生成没提示词，表单约束移到服务端按分支校验 |
| `media/entity/MediaGenTask.java` | `TYPE_CONTEXT_IR`/`TYPE_REGENERATION` 常量 | task_type 列是 VARCHAR 无 CHECK，新类型零迁移直写 |
| `media/service/MediaGenTaskService.java` | doSubmit 附属分流 / `requireRegenerationSource` 七连校验 / `estimateContextIrPoints`（CHAT 公式） / estimatePreview promptChars 分支 | 收银台分流：再生成先验「源小票」（归属/成功/同店/7 天内），增强按字数估 token 价 |
| `media/service/MediaGenTaskWorker.java` | buildRequest 双形态 sourceArkTaskId 解析 / `handleContextIrSucceeded`（文本落库+CHAT 结算+fail-closed 退款） | 后厨：查到「文本型成功」不下载视频，改存 .md；估扣多了按实退 |
| `billing/service/MediaBillingService.java` | `settleMediaSuccess` 14 参重载（+tokensOutput） | CHAT 计费要「进/出」两条腿分开算钱，视频路径走 13 参老口不受影响 |
| `media/service/MediaStorageService.java` | `storeText`（1MB 上限，.md，同 storeStream 咽喉点） | 增强文本也走统一入库通道：防路径穿越+记 owner |
| 测试 | `MinimaxVideoProviderTest`(20)/`DashscopeVideoProviderTest`/`MediaGenTaskServiceTest`(47)/`MediaGenTaskWorkerTest`(25)/`MediaBillingServiceTest`(26) | 附属链各环节 MockWebServer/单测 |

## 前端文件

| 文件 | 作用 | 大白话 |
|---|---|---|
| `api/media.ts` | 类型扩（ratio 9 值/新 taskType/sourceTaskId/promptChars）+ `isContextIrModelId`/`isRegenerationModelId` + `fetchMediaText` | 前后端共用一套「模型名后缀=功能」暗号；拉 .md 文本走带 token 的 axios |
| `views/VideoGenView.vue` | 再生成源任务选择器模式 / context-ir 精简表单 / 能力驱动显隐修复（越界回退=能力首档）/ CONTEXT_IR 文本结果+下载 / 历史列「增强文本」tag | 表单像变形金刚：选啥模型长啥样。选「再生成」整个表单缩成一个下拉 |
| `views/CanvasView.vue` | submitVideo ratio 不再硬补 '16:9'（i2v 无比例档会被拒） | 以前无脑塞默认值，现在尊重模型的「体检表」 |
| `components/canvas/PropertyPanel.vue` | 画布视频模型下拉过滤附属档 + i2v 空比例隐藏控件 | 画布节点形态装不下附属功能，入口只留独立页 |
| `views/VideoGenView.test.ts` | M1-M5 联动用例（t2v 隐上传区/i2v 隐比例/再生成极简载荷/context-ir 预估/文本结果） | 联动回归网：防「只写正向必漏反向」 |

## 表与 SQL 注解

只**读**既有表，新数据靠 V166 种子（不建新表）：

- `llm_providers` +2 行：`name='happyhorse'`（protocol=dashscope，**config.queryEndpoint 必须显式**——中转查询在 `/v1/tasks`，默认推导会拼出错误的 `/api/v1/tasks`）、`name='minimax-h3'`（protocol=minimax）。`api_key_enc=''` 等管理员填。生活比喻：两家店入驻商场，柜台/电话都登记好，就差店主钥匙。
- `pricing_rule` +6 行（provider 维度判重幂等）：VIDEO SECOND 分档 JSONB `{"768p":0.5,"2k":0.8}` 这类行内槽；**context-ir 用 kind=CHAT**（输入/输出分价 5.8/23 每百万——VIDEO 的 TOKEN 单价模式表达不了双腿计费，故落聊天价表页）。
- `media_gen_tasks.task_type`：VARCHAR(16) 无 CHECK 约束——CONTEXT_IR/REGENERATION 直接写，零迁移（设计时埋的顺路）。

## 踩坑批注

1. **context_ir 结果识别**：中转查态响应不总有 `task.task_type` 字段 → 加启发式「content.prompt 有 && content.url 无」即文本结果，双保险（plan 偏离：原计划 queryTask 接口重载，实际在 parseQueryResult 内探测，零接口改动——已回填 plan/开发进度）。
2. **regeneration 源 id 双形态**：平台 config 存 `sourceTaskId`（平台 id，留痕）+ `sourceArkTaskId`（上游 id 字符串，出站用）；worker 解析数字或纯数字字符串两形态（ark_task_id 是 varchar）。
3. **i2v 比例脏值**：旧 `applyCapabilityConstraints` 硬编码回退 '16:9'/'720p'，在 minimax（仅 768p/2k）留「表单值∉候选」脏值→提交被拒；修为回退能力清单首项。
4. **context-ir 时长**：官方无 duration 参数，但平台校验带要求非空 → 前端固定发 5 仅过校验，不入出站体。
5. **happyhorse queryEndpoint**：见上，缺失=查询打错地址无限退避（V166 注释原话）。

## 变更记录

- 2026-08-30 首版（Chunk A-H，commit 9ae3894/fd4ef49/96e31438/51a314e8/6e95040c/c52d486e…）。
