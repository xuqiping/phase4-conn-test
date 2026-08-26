# 人工测试遗留问题修复设计 II

> 日期：2026-08-26（SDD Phase 1 规格先行）
> 来源：`workflow_output/人工测试问题/` 下 7x/8x/9x/12x 四份文件的「未解决」清单共 10 项。先全量核查代码现状（6 路并行探查，事实均带 file:line），再给修复设计。
> 本文为唯一真相源：Phase 2/3 实现与本文冲突时，要么改实现、要么改本文并记录原因。

---

## 0. 总览：未解决项核查结论

| # | 出处 | 问题摘要 | 核查结论 | 修复章节 |
|---|---|---|---|---|
| 7x-2 | 积分 | 预估 3750 实耗 4300+，组流水/已使用只记 3750，账单记 4300 | **口径分裂（钱 bug，最高危）**——补差腿组池失败转 BACKSTOP 后：差额扣组长个人、组池不回补、成员 used 不加；账单 usage 却按实耗全额挂成员。顺带查出：FAILED usage 行丢 project_group_id；参考视频加价不进预估 | §1 |
| 8x-1 | 审计 | 邮件发送（注册+忘记密码）没进日志 | **真缺口+死注释**——`EmailVerifyController` 注释声称「审计在 Service 内手工建行」，实际零代码；字典码位已预留从未激活 | §7 |
| 8x-2 | 审计 | 还存在英文模块描述 | **4 整模块缺失**——`security`/`feedback`/`project-group`/`audit` 未进字典；另约 40 个动作码缺失；前端模块下拉写死 13 项与后端实际 18 模块不齐 | §7 |
| 8x-3 | 审计 | 检查是否还有操作不进审计 | **大面积缺口**——P0 邮件/短信/微信/MFA 端点全无；P1 管理端敏感写（部门/Provider/工作流/审批/智能体/用户级密钥）全无；P2 用户资源写大多无 | §7 |
| 9x-1 | 对话 | usage 记缓存 tokens + 价表配缓存 token 每百万价 | **全链 6 层缺**——TokenUsage DTO、两 Provider 解析、usage 表、采集器、价表列、计价腿全无缓存维度 | §6 |
| 7x-5 | 积分 | LLM 价表加闲时输入/输出/缓存价（不填默认用忙时）+ 每日闲忙时段配置（分是否周末） | **零先例**——价表无时间维度、系统无时段配置结构；system_settings JSON 先例可挂载 | §5 |
| 7x-4 | 积分 | 新增视频价表还分 480P/720P 档，应只按有/无视频参考区分 | **三层深植**——resolution 列+索引+命中链+候选展开+前端写死；TOKEN 模式计价已不看分辨率，仅 SECOND 模式选行用 | §4 |
| 7x-1 | 积分 | 账单总览里项目组分配/对账没区分具体项目组 | **半缺口**——「项目组分配」tab 组筛选已在（D3 `e791c2d5` 交付：后端 groupId 参数+前端组下拉+首列组名）；真缺口在「项目组对账」tab：零筛选参数+按 Q9=A 口径 service 层只返回异常组，正常组看不到自己的对账行 | §2 |
| 7x-3 | 积分 | 所有积分变动（消耗/预扣/充值/划拨）不实时，要手动刷新 | **真缺口**——无任何服务端主动推送通道（WS 仅聊天流式、SSE 全是请求作用域、Redis 零 pub/sub）；但 WS 基建约 70% 可复用，前端余额已有单一真相源雏形 | §3 |
| 12x-1 | 认证 | 邮箱验证码间隔多久？刷新后按钮立即可点，再点会发成功吗 | **后端有兜底、前端无恢复**——60s 固定窗口内后端真拒（429、不发信、不耗日额度）；缺口：话术写死「60 秒」非真实剩余、前端倒计时纯内存态刷新即丢、429 后按钮仍可点反复触发；另有被拒也吃滑块失败数/IP 配额、双 toast 三个次生恶化点 | §8 |

**非目标**（本期不做）：
- 支付宝/微信真实支付接入（等商户密钥，见 7x 文件内指南）。
- Doubao Coding 套餐 404（待运维确认 API Key 套餐，平台侧配置入口已在）。
- 短信找回密码后端（上期 Q8 已明确不排）。
- 视频/图片模型的闲时价（本期闲时价仅文本类 LLM；媒体闲时价需要时下期另立）。

---

## 0.5 开放问题速览（大白话版，逐条拍板即可）

| 编号 | 一句话问题 | 选项 | 推荐 |
|---|---|---|---|
| Q1 | 补差时组池没钱、差额记到了组长个人头上，组里的「已使用」要不要把这部分也算进去 | A 算进去——组侧已使用=真实消耗，和账单对得上 / B 账单把这条拆成两行（组里扣的+组长兜底的）/ C 不动数据，只在页面上加说明 | A |
| Q2 | 积分实时刷新用什么通道 | A WebSocket 推送——变动一发生毫秒级推到浏览器，开发量中等 / B 前端 15-30 秒轮询——最简单，延迟=轮询间隔 | A |
| Q3 | 视频价表的分辨率档（480P/720P/1080P/4K 分行） | A 彻底移除——表单不再出分辨率维度，存量分行合并成一行（按有/无参考两种）/ B 只是界面隐藏，数据能力保留以后还能用 | A |
| Q4 | 闲时价适用哪些模型 | A 仅对话（CHAT）/ B 对话+向量/重排（文本类都支持） | B |
| Q5 | 审计补齐范围 | A 本期只补 P0（邮件/短信/微信/MFA）+ P1（部门/模型供应商/工作流/审批/智能体/用户级密钥等管理端敏感写），P2（画布/资产/项目等用户资源写）P3（记忆模块）下期 / B 一次全上 | A |
| Q6 | 缓存命中的 token 怎么计费 | A 从输入 token 里扣出来按缓存价单算（上游账单口径，不双算）/ B 不扣，缓存价当附加费（会双算） | A |
| Q7 | 刷新页面后倒计时怎么恢复 | A 浏览器本地存「倒计时截止时间」+ 被拒响应带真实剩余秒数（不加新接口）/ B 新增一个「查剩余冷却秒数」接口 | A |

## 我的选择
Q1：A，另外回答我一个问题，如果组长的积分也不够扣了会怎么处理？
Q2：A
Q3：A
Q4：B
Q5：B
Q6：A
Q7：A

---

## 1. HOLD 补差组侧口径分裂（7x-2）——钱 bug

### 1.1 问题原文

> 积分预扣有问题，项目组内，比如我生成一个 15 秒的视频，1080P 预计消耗 3750 积分，实际消耗是 4300 多积分，但是项目组内流水和已使用积分只记了 3750，而账单里显示这条视频消耗了 4300 多积分。

### 1.2 现状事实

**结算/补差链**（`MediaBillingService.settleMediaSuccess`）：

| 事实 | 位置 |
|---|---|
| 实耗 = computeCost(hasReference, resolution) → toPoints；diff = actual − heldPoints，>0 补扣 / <0 退差 / =0 不动 | `MediaBillingService.java:247-252` |
| 补差·组腿：`chargeGroup(gid, uid, diff, kind, refId, "media-settle-"+refId)`——组池条件扣减+成员 addUsed+组账本 CONSUME 三件套俱全 | `MediaBillingService.java:254-257`；`ProjectGroupWalletService.java:108-136` |
| **补差·组腿抛 BusinessException → `backstopMedia`：差额扣组长个人（余额够 charge / 不够 chargeToDebt），组账本只记一条 BACKSTOP(−diff) 行；组池不回补、成员 used 不加** | `MediaBillingService.java:258-263`；`ProjectGroupWalletService.java:170-193` |
| 「BACKSTOP 不计 member.used」是 V133 刻意设计（类注释「对账不变量②」） | `ProjectGroupWalletService.java:32-34、165-169` |
| usage 记**实耗全额 4300**（含 projectGroupId）挂提交成员名下——账单看到的就是这行 | `MediaBillingService.java:292-294` |
| 退差腿对称完整无漏步：refundGroup 无条件回补组池+回减成员 used+记 REFUND 行 | `ProjectGroupWalletService.java:141-163` |

**展示口径**：账单（用户积分明细/调用明细）读 `llm_usage_logs`（实耗 4300，`BillingQueryService.java:105-119`）；组内流水读 `project_group_ledger` 全类型行（`ProjectGroupQueryService.java:78-96`）；已使用积分 = `project_group_members.used_points` 列。

**初始 HOLD 预扣**（对比基准）：`holdMediaEstimated` 走同一 `chargeGroup`，组池 −3750、成员 used +3750、组账本 CONSUME(−3750) 一个事务落齐（`MediaBillingService.java:206-220`）。

**顺带查出的次生问题**：

| 问题 | 位置 |
|---|---|
| 结算失败/计费失败的 FAILED usage 行不带 project_group_id（record 少传该参，本地库 task 59 组任务 FAILED 行 gid 为空可证） | `MediaBillingService.java:145-146、299-300` |
| 预估命中链：「有参考行未配 est」时回落**无参考行**的 est JSON，而实耗按**有参考行** token 价计——参考视频加价不进预估 | `PricingService.java:111-127` |

**差额 550 的来源**（预估 3750 vs 实耗 4300）：预估走 `estimateVideoYuan`（管理员配置的 est_per_resolution ¥/秒 × 时长），实耗走 `computeCost`（Ark 返回真 token × token 单价）——两套人工配置天然不闭合，est 配低约 13% 即出现 3750/4300；且 Ark token 曲线随模型版本漂移（本地实测 720p 5s 返 108,900 token ≈ 21,780 tok/s，而 worker fallback 表写 61,760 tok/s，差 2.8×）。多退少补机制本就是为吸收这套口径差设计的。

### 1.3 定性

**不是「补差腿漏写同步」**——补差组腿代码是全的。唯一路径是：补差时组池余额/成员限额/管理可分配任一不足 → chargeGroup 整体回滚 → BACKSTOP 兜底把差额移出组账（组长个人承担），但账单 usage 仍按实耗全额挂成员——**两条口径没有汇合点**，形成「组侧 3750 vs 账单 4300」的账实分裂。

### 1.4 修复设计

**核心（Q1=A 拍板后）：BACKSTOP 差额计入成员 used，组侧如实反映实耗。**

> Q1 追问已答（2026-08-26）：组长个人也不够扣时——现网代码已实现三级兜底：组长余额扣到 0 + 差额挂组长 DEBT 欠款（`chargeToDebt`），欠款期间组长消费入口全拒，充值/发放自动冲抵（`ProjectGroupWalletService.java:170-184`，V157 上期落地）。链条终点 = 组长欠款，无平台担损。本修复的 used 计入不受组长侧结局影响。

1. **`ProjectGroupWalletService.backstop` 增两处**：
   - 签名增加消费成员 `userId` 参数（现签名只有组长侧上下文，取不到「谁花的」——两个调用方 `MediaBillingService.backstopMedia:384-392` 与全量结算路径 `:118-127` 都持提交人 uid，透传即可；该修复同时覆盖补差 BACKSTOP 与全量结算 BACKSTOP 两条路径，它们共用此 funnel）。
   - 兜底扣组长个人成功后，对提交成员行**无条件** `UPDATE used_points = used_points + shortfall`（新 mapper 方法 `addUsedUnconditional`——不能用现有条件版 `addUsed`，quota 紧时会被自己卡住；used 本来就允许超过 quota，超限只在扣费入口卡）。效果：成员 used = 3750 + 550 = 4300 = 账单实耗，口径汇合。
2. **对账不变量②改写**：旧「BACKSTOP 不计 member.used」改为「BACKSTOP 计入 member.used（used = 成员名下真实消耗，不论资金来源是组池还是组长兜底）；组池 balance 仍不含 BACKSTOP（资金确实出自组长个人）」。§2 对账恒等式（组池侧）不受影响——BACKSTOP 本就不动组池。
3. **组账本 BACKSTOP 行 remark 明示**：补记「补差兜底，差额由组长个人承担」文案（新行起；`BillingReconcileService` crossDiff 口径不变）。
4. **存量数据修复**（迁移内一次性）：找 `project_group_ledger` 中 BACKSTOP 行对应的成员行，把历史 BACKSTOP 差额补加进 used_points（按 ref_id → taskId → media 任务提交人映射；映射不到的行跳过并计数记录，宁缺勿错）。
5. **次生修复**：
   - FAILED usage 行补传 projectGroupId（`MediaBillingService.java:145-146、299-300` 两处 record 调用加参）。
   - 预估参考加价：估价命中链保持不变（有参考行未配 est 仍回落无参考行——这是合理兜底），改为**价表配置页 TOKEN 模式 est 输入旁显示「近 7 天同规格实耗/预估平均偏差 %」提示**（聚合 llm_usage_logs，管理员据此校准 est，收窄多退少补幅度）。此为校准工具非硬修复，列为本章附带项。

**效果核验**（问题原话场景复演）：组池够扣时补差走 chargeGroup（现状已对）；组池不够时——组内流水 = CONSUME(−3750) + BACKSTOP(−550)，已使用积分 = 4300，账单 = 4300，三者闭环。

### 1.5 数据模型

无表迁移（used 语义变化+新 mapper 方法）。存量修复走 V158 数据段（见 §9）。

### 1.6 测试策略

- 单测：① 组池富余补差——used/组池/账本三侧数值断言；② 组池不足触发 BACKSTOP——组长个人扣款、成员 used 含差额、组池不变、账单=实耗；③ addUsedUnconditional 不被 quota 卡；④ 退差腿回归不受影响。
- 人工测试（钱路径必过门槛）：复演问题原话——组内提交 15s 1080P 有参考视频（HOLD 3750）→ 让组池在结算前耗到 < 550 → 结算后核对：组流水两条、已使用 = 实耗、账单 = 实耗、组长个人余额/欠款含 550。

### 1.7 安全考量

- backstop 内新 UPDATE 与组长个人扣款同事务；无条件加 used 无资金语义（used 是统计列），不构成超扣面。
- 存量修复脚本只加不减、映射不到跳过，可重复执行（幂等：按「BACKSTOP 行是否已计入」标记或差额计算幂等设计）。

---

## 2. 对账/分配视图按组下钻（7x-1）

### 2.1 问题原文

> 账单总览里，项目组分配、项目组对账，没有区分具体的项目组。

### 2.2 现状事实

| 事实 | 位置 |
|---|---|
| 「项目组分配」tab：后端 `GET /billing/admin/group-allocations` 已有 `groupId` 精确筛组参数 + keyword；SQL 带 `AND m.group_id = #{groupId}`；VO 每行带 groupId/groupName | `BillingController.java:195-203`；`GroupAllocationMapper.java:29、65`；`GroupAllocationRowVO.java:13-25` |
| 前端分配 tab 已有组下拉（groupOptions，placeholder「全部项目组」，clearable）+ 查询传 groupId + 变化自动重查；表首列即组名 | `BillingAdminView.vue:194-214（下拉 :203-209）、729、812-814、696-720` |
| 以上在 D3 `e791c2d5` 本身就已交付（当前工作区无未提交改动） | `git show e791c2d5` |
| 「项目组对账」tab：端点**零入参**；service 层按 Q9=A 口径只把 diff≠0 或 crossDiff≠0 的组放进返回列表，正常组行被丢弃 | `BillingController.java:153-157`；`BillingReconcileService.java:82-129（过滤 :106-117）` |
| 对账前端零筛选器，表格数据源 = abnormalGroups | `BillingAdminView.vue:230-239、247-253、759-768` |
| 底层 SQL `selectGroupRawRows()` 本就是每组一行的全量哑聚合（含 groupId/groupName），补齐只需 service/端点放开 | `GroupReconcileMapper.java:26-54` |
| 组下拉数据源现成：`GET /billing/admin/project-group-options`（id+name），分配 tab 与调用明细 tab 已共用 | `BillingController.java:121-125`；`BillingAdminView.vue:85-92、796-804` |
| 「合计卡跟随筛选」先例：用户余额 tab 已确立（7x 上期 #3） | `BillingAdminView.vue:177` |

### 2.3 缺口定性

「项目组分配」tab 按组筛选**已存在**——若用户反馈针对该 tab，大概率是反馈时看的旧部署（需向用户核实，本节仍列一条轻量核对项）。「项目组对账」tab 是完整缺口：零筛选+只回异常组，账平的组想看自己的净额/消耗/期望余额完全看不到。

### 2.4 修复设计

**后端** `GET /billing/admin/group-reconcile` 加两个可选参数（向后兼容，不传=现状）：

- `groupId`（Long，可选）：SQL 加 `WHERE g.id = #{groupId}`——选中组时**返回该组行**（不论异常与否）。
- `includeAll`（Boolean，默认 false，可选）：true 时返回全部组行（每组一行）；false 保持 Q9=A「仅异常组」默认视图。

响应结构扩展：`abnormalGroups` 字段名保留（兼容），新增 `groups[]`（选中 groupId 或 includeAll 时填充，行结构同 GroupReconcileRowVO + `balanced` 布尔）；`totals` 六项合计**跟随筛选**——选中组时合计=该组数值（沿用用户余额 tab「筛选谁合计谁」先例），未筛选=全平台。

**前端**对账 tab：

1. 复用 `groupOptions` 加组下拉（与分配/调用明细 tab 同款）+ 「显示全部组」开关（n-switch，映射 includeAll）。
2. 表格数据源改 `groups ?? abnormalGroups`；行加「状态」列（balanced ? 平 : 异常 tag）。
3. 顶卡合计随筛选联动刷新（watch 组下拉重查）。
4. 「项目组分配」tab：与用户核对版本后无代码改动；若确认看的旧版则在勾销单里注明即可。

### 2.5 数据模型

无迁移、无新端点（扩展现有响应与参数）。

### 2.6 测试策略

- 单测：groupId 过滤命中/未命中；includeAll 全组行数=组数；totals 跟随筛选的数值断言；不传参回归 Q9=A 现状。
- 人工测试：① 对账 tab 选一个账平组 → 能看到该组完整行且状态「平」，顶卡合计=该组数值；② 清空筛选 → 回到「仅异常组」默认视图；③ 打开「显示全部组」→ 全组行可见，异常行标红。

### 2.7 安全考量

只读端点扩参，权限注解不变（billing admin）；参数预编译。

---

## 3. 积分变动实时刷新（7x-3）

### 3.1 问题原文

> 所有的（个人/项目组）积分消耗（含预扣），管理员积分充值、项目组内积分划拨等操作，都无法实时刷新，都需要我刷新一下才能看到，有没有办法直接实时刷新？

### 3.2 现状事实

**后端**：

| 事实 | 位置 |
|---|---|
| `spring-boot-starter-websocket` 已引入；`@EnableWebSocket` 注册唯一端点 `/ws/chat`（Origin 白名单复用 CORS 配置） | `pom.xml:60`；`WebSocketConfig.java:32、45-47` |
| WS 握手 JWT 鉴权拦截器（query `?token=` 或 Bearer → userId 入 session attributes）——可直接复用 | `WebSocketAuthInterceptor.java:26-60` |
| `ChatWebSocketHandler` 聊天专用、按 sessionId 索引、无按 userId 广播能力、无心跳/重连 | `ChatWebSocketHandler.java:22、27、30-36` |
| 三处 SseEmitter（chat/kb/workflow）全是「一次请求→流式回包」请求作用域，无事件广播通道 | `ChatController.java:264-265` 等 |
| Redis 零 pub/sub 先例（无 RedisMessageListenerContainer/convertAndSend） | 全库 grep |
| Spring 应用内事件先例：`SecurityEventPublisher` 发事件 + `@EventListener` 异步消费——可照抄 | `SecurityEventPublisher.java:24、35`；`SecurityMonitorWorker.java:43` |

**事件源汇聚点**：所有积分变动最终都过两个 service——`PointsWalletService`（charge/chargeIdempotent/refundIdempotent/grant/grantIdempotent/chargeToDebt/refund/debitForGroupAllocate/creditForGroupReclaim/creditRechargeForOrder，`PointsWalletService.java:95-437`）与 `ProjectGroupWalletService`（allocate/reclaim/chargeGroup/refundGroup/backstop，`ProjectGroupWalletService.java:54-217`）。管理员充值（WalletAdminController→grantIdempotent）、支付回调（PaymentNotifyController→creditRechargeForOrder）、聊天/媒体 HOLD 与结算（LlmBillingService/MediaBillingService）、组划拨全部经过。**UsageCollector 已示范「扣减与采集分离」fire-and-forget 哲学。**

**前端**：

| 事实 | 位置 |
|---|---|
| `projectGroup` store 已承载全局余额单一真相源雏形：`personalPoints` + `groups[].balancePoints`，AppHeader 双徽标读它 | `stores/projectGroup.ts:23、31、45-54`；`AppHeader.vue:15-33、116-120` |
| 徽标取数：onMounted + 路由切换轻刷新，注释明言「无轮询」 | `AppHeader.vue:116-120` |
| MyWalletView / ProjectGroupsView 各自直接调 api，onMounted 一次性；组页操作后手动补刷 | `MyWalletView.vue:240-247`；`ProjectGroupsView.vue:539-542、584、782、1224` |
| 生成页/画布预估区 balance 来自 estimate 接口每次响应（防抖天然带新值）——**无需推送** | `ImageGenView.vue:552、591-595` 等 |
| 原生 WebSocket 已在用（聊天 `/ws/chat?token=`），无自动重连 | `stores/chat.ts:565-584` |
| 轮询先例 6+ 处（3s 铃铛×2、2s 支付单、60s 侧边栏、2.5-3s 任务进度） | `MemoryNotificationBadge.vue:109-111` 等 |
| 无 socket.io/sockjs/stomp 依赖，无需新增 | `package.json:13-28` |

### 3.3 修复设计（Q2=A 拍板后：WebSocket 推送）

**后端三件**：

1. **新端点 `/ws/events`**：`EventsWebSocketHandler`（按 `userId → Set<WebSocketSession>` 索引，`ConcurrentHashMap`），注册进现有 `WebSocketConfig`（与 `/ws/chat` 并列，复用 `WebSocketAuthInterceptor`）。心跳：服务端每 30s ping，3 次未 pong 踢除；前端 pong 回显。
2. **事件发布**：新建 `PointsChangedEvent {userId, scope(PERSONAL|GROUP|MEMBER), groupId, balanceAfter, delta, reason}`——在 `PointsWalletService.adjust`（个人腿唯一咽喉）与 `ProjectGroupWalletService` 五个写方法尾部发布，走 `ApplicationEventPublisher`（照抄 SecurityEventPublisher 范式，@Async 监听，与扣减事务解耦、失败仅 WARN 不回滚计费）。
3. **推送**：`@EventListener` 监听 → handler 按 userId 推 JSON：`{type:"points.changed", scope, groupId?, balanceAfter, delta, reason, ts}`。多端登录同 userId 全部收到（Set 索引天然支持）。

**前端三件**：

1. `projectGroup` store 增 `connectEvents()`：登录后建 `new WebSocket('/ws/events?token=')`，onmessage 更新 `personalPoints` / 对应组 `balancePoints`——AppHeader 徽标自动响应；断线指数退避重连（1s/2s/5s/30s 封顶），**重连成功后强制 `loadWallet()+loadGroups()` 一次**（补断线期间漏推）。
2. `MyWalletView` / `ProjectGroupsView`：监听 store 的变更（或 store 暴露 `lastEvent` ref）→ 防抖 1s 重查流水/列表（列表重查成本高，只在页面可见时执行）。
3. 通知铃铛 3s 轮询**顺带替换**为同一通道（`type:"notification"` 事件或保持现状——实现期看工作量，非本章验收项）。

**多实例部署**：当前单实例，事件为进程内 Spring 事件即可；未来多实例时加 Redis pub/sub 转发（依赖已在），本期不做，记录为扩展点。

**备选方案 B（轮询）留档**：AppHeader `loadWallet` 加 15-30s setInterval，与现有代码风格一致成本最低，代价延迟=间隔。Q2 若改选 B 则本章后端全砍。

### 3.4 数据模型

无迁移。

### 3.5 测试策略

- 单测：事件发布覆盖（个人 charge/grant/refund、组 allocate/chargeGroup/backstop 各发一次且数值正确）；监听异步不阻塞计费事务（计费成功事件失败不回滚）。
- 人工测试：① A 页面挂着，B 端（或管理员）给账号充值 → A 页徽标毫秒级跳动；② 组内成员生成任务 HOLD → 组长/成员侧组池与 used 即时变；③ 断网 10s 恢复 → 重连后余额自动补齐正确；④ 未登录/过期 token 连 `/ws/events` → 握手拒绝。

### 3.6 安全考量

- 复用 JWT 握手拦截器，未认证连不上；事件只推给本人 userId（不跨用户）。
- 推送内容仅数值与 reason 码，不含敏感明细。
- Origin 白名单沿用 CORS 配置，防跨站 WS 劫持。
- 异步监听器异常只 WARN——推送失败不影响计费正确性（推送是显示层优化，DB 是真相源）。

---

## 4. 视频价表去分辨率档（7x-4）

### 4.1 问题原文

> 现在在价表配置里，新增价表的时候，Cdance2.0 还给我区分了 480P、720P 等等，现在已经统一设置了啊，不需要分这些了，在最后有视频参考/无视频参考里统一设置就行了。

### 4.2 现状事实

分辨率维度三层深植：

| 层 | 事实 | 位置 |
|---|---|---|
| 数据层 | `pricing_rule.resolution` 列（仅 VIDEO SECOND 行有意义，NULL=通用兜底行）+ 专属索引 (kind, model, has_reference, resolution, effective_from DESC) | `V152__pricing_resolution_estimate.sql:8-17` |
| 服务层 | `resolveRule` 四级命中链：精确(参考面,分辨率)→(参考面,通用)→(无参考,分辨率)→(无参考,通用)；分辨率归一化；**分辨率只参与「选哪一行」，不参与算式**；TOKEN 模式 resolution 强制 null（计价已不看分辨率） | `PricingService.java:107-135`；`PricingConfigService.java:472-478` |
| 配置层 | 后端候选展开把 VIDEO 模型拆成 参考面×(通用+4 档) 最多 10 槽位；分辨率槽位集写死 `List.of("480p","720p","1080p","4k")`；判重 SQL countConflictingProviderModelHasRefResolution | `PricingConfigService.java:226-228、266-306、463-467` |
| 前端 | 分辨率下拉写死 `[通用/480p/720p/1080p/4K]`，仅 SECOND 显示；TOKEN 模式 est 槽位（est_per_resolution JSON，预检估价用不参与真实扣费） | `PricingConfigView.vue:86-89、91-103、161-184` |
| 伴生 | 导出/导入/模板结构带 resolution 字段 | `PricingRuleExportItem` |

### 4.3 修复设计（Q3=A 拍板后：彻底移除 SECOND 分辨率维度）

**口径先行**：视频价行身份从 `(provider, model, kind, hasReference, resolution)` 收敛为 `(provider, model, kind, hasReference)`——「有参考/无参考」两行制，分辨率彻底退出身份键。TOKEN 模式的 `est_per_resolution`（预检估价 JSON）**保留不动**：Ark 真 token 数随分辨率显著变化（1080p ≈ 59K tok/s vs 720p ≈ 22K tok/s），预检精度仍需要分档，且它与真实扣费无关（用户主诉的「分行」来自 SECOND 秒价行的候选展开，不是 est 输入）。

1. **表单**：`PricingConfigView` SECOND 分支删分辨率下拉，一行一价（有参考/无参考各一行）；TOKEN 分支 est 槽位输入原样保留。
2. **候选展开**：`availablePricingModels` VIDEO 候选收敛为 参考面×2（不再 ×5 档），hint 同步改。
3. **判重/校验**：`countConflictingProviderModelHasRefResolution` 及保存校验去掉 resolution 维；`VIDEO_RESOLUTION_SLOTS` 常量仅保留给 est JSON 校验用（或改名 EST_RESOLUTION_SLOTS）。
4. **询价**：`findEffectiveWithResolution` 与命中链保留（resolution 恒 null 后自然全部走「参考面+通用」行，老代码兼容不动，降低回归面）。
5. **存量数据迁移**（V158 数据段）：同 `(provider_id, model, kind=VIDEO, has_reference)` 存在多行 SECOND 分辨率行时——保留 `effective_from` 最新一行，其余逻辑删除；保留行 resolution 置 null。**价不同的冲突行迁移前打印清单**（迁移日志记录被合并行的价，供管理员核对最新价是否符合预期——按「最新生效优先」规则取，不猜价）。
6. **导入/导出/模板**：SECOND 行 resolution 字段导入时忽略（向后兼容旧导出文件），导出不再带。
7. **resolveRule 兜底语义**：合并后若有任务仍带 resolution 请求（历史在途任务结算），命中链 `(参考面,通用)` 兜底——天然兼容。

### 4.4 数据模型

V158：`pricing_rule` 数据迁移（SECOND 分辨率行合并）+ 索引重建为 (kind, model, has_reference, effective_from DESC)（去 resolution 段）。列本身保留（历史行引用+est 语义无关，删列收益小于风险）。

### 4.5 测试策略

- 单测：① 候选展开 VIDEO 只出 2 槽位；② 判重不再看 resolution；③ 带 resolution 的结算请求命中通用行；④ 迁移合并规则（多行取最新、单行置 null、无 VIDEO CHAT 行不动）。
- 人工测试：① 新增 Cdance2.0 价表 → 表单只有「是否含参考视频」两行，无分辨率选项；② 存量有分辨率差异价的模型迁移后 → 价表页只显示一行（最新价），生成页估价/实耗正常；③ 旧导出文件导入 → 成功且 resolution 被忽略。

### 4.6 安全考量

存量合并不可逆（逻辑删除可恢复）；fail-closed 语义不变（无价仍抛 PRICING_NOT_FOUND，含工作区已加固的估价 0 拒绝路径）。

---

## 5. LLM 闲时价与闲忙时段（7x-5）

### 5.1 问题原文

> llm 模型的价表配置里，再给我增加闲时的输入输出与缓存价格配置（不填默认就全用原本的），还需要让我配置每日的闲时，忙时时间段，并分是否周末。

### 5.2 现状事实

| 事实 | 位置 |
|---|---|
| 价表行只有 input/output 两个百万价，无时间维度；`effective_from` 是「生效日期」非「每日时段」 | `V66__billing_pricing.sql:25-30` |
| `computeCost` CHAT = (in/1M)×pin + (out/1M)×pout，无调用时刻入参；调用方（holdChat/settleChatHeld/onSuccess）均不传时间 | `PricingService.java:92-98、206-216`；`LlmBillingService.java:153-217` |
| system_settings 有 JSON 值先例（rag.memory.entities-config 等）与「计费设置」分区先例（GET/PUT /api/settings/billing） | `SystemSettingService.java:26-28、199-217、889-907`；`SystemSettingController.java:120-131` |
| 无任何时段/时间窗配置先例（唯一时段逻辑 OffHoursSensitiveRule 00:00-06:00 硬编码） | `OffHoursSensitiveRule.java:19-20` |
| 前端 SettingsView 已有「计费设置」tab（admin） | `SettingsView.vue:6-39` |

### 5.3 修复设计

**适用范围**（Q4=B 拍板后）：CHAT + EMBED/RERANK 文本类价表行（视频/图片不做，见非目标）。

**1. 价表列**（V158）：`pricing_rule` 增三列，全部 `NUMERIC(12,4) NULL`：
- `off_peak_input_per_million`、`off_peak_output_per_million`、`off_peak_cached_per_million`（后者与 §6 缓存价联动）。
- **NULL/空 = 用忙时价**（用户原话「不填默认就全用原本的」）——向后兼容，存量行为零变化。

**2. 时段配置**：system_settings 键 `billing.off-peak.schedule`，JSON：

```json
{
  "enabled": true,
  "timezone": "Asia/Shanghai",
  "weekday": [{ "start": "22:00", "end": "08:00" }],
  "weekend": [{ "start": "00:00", "end": "24:00" }]
}
```

- `weekday` = 周一~周五闲时窗口数组（每天同一套，用户原话「每日的闲时忙时时间段」）；`weekend` = 周六日独立窗口。
- `end <= start` 视为跨零点（22:00→08:00 覆盖 22:00-23:59 + 00:00-07:59）。
- 周末判定按 timezone 的星期几（不是 UTC）。
- 校验：窗口 HH:mm 格式、数组 ≤4 段、enabled=false 时全走忙时。
- 前端：SettingsView「计费设置」tab 加「闲时时段」配置卡（enabled 开关 + 工作日/周末各一组窗口编辑器）。

**3. 计价**：`PricingService` 增 `isOffPeak(LocalDateTime moment)`（读上述配置，每次实时查库与 SystemSettingService 现有哲学一致）+ 选价私有方法：闲时且闲时价非空 → 用闲时价列，否则忙时价列。`computeCost`/`textCost` 内部调用 `isOffPeak(now)`，**签名不变**（不改调用方）；单测通过新增的重载传显式时刻。

**4. HOLD 跨时段语义**（记录取舍）：聊天 HOLD 按发起时刻价预扣，结算按完成时刻价多退少补——跨越闲忙边界的长回答天然被多退少补吸收，不额外处理（hold 时无法预知完成时刻）。

**5. 价表表单**：PricingConfigView 文本类（CHAT/EMBED/RERANK）行增「闲时输入价/闲时输出价/闲时缓存价」三个可空输入（placeholder「留空=同忙时」）。

### 5.4 数据模型

V158：`pricing_rule` +3 列（NULL）；system_settings 无迁移（upsert 即用，前端保存时写入默认 disabled 行可选）。

### 5.5 测试策略

- 单测：① isOffPeak 全矩阵（工作日窗口内/外、周末窗口、跨零点窗口、enabled=false、无效配置回退忙时）；② 闲时价 NULL → 忙时价；③ 闲时价配置 → CHAT/EMBED 计价取闲时列；④ HOLD 忙时发起闲时结算的多退少补数值。
- 人工测试：① 配 22:00-08:00 闲时 + 闲时输出价减半 → 闲时时段发起聊天，账单/明细按闲时价；② 时段外发起 → 忙时价；③ 闲时价留空 → 行为与改前完全一致（回归）；④ 改时段配置即时生效（无需重启）。

### 5.6 安全考量

- 时段配置写权限挂现有计费设置 admin 权限（GET/PUT /api/settings/billing 同级）+ @AuditLog（system:update 类码，§7 字典一并补）。
- 配置解析失败回退忙时（fail-safe 到贵价侧对平台无损、对用户多退少补可退）。
- 无新外部依赖。

---

## 6. 缓存 token 记录与计价（9x-1）

### 6.1 问题原文

> 现在有 usage 可以记录输入输出 tokens 了，能不能获取缓存 tokens？在积分系统中还需要对 llm 模型的缓存 tokens 设置每百万的价格。

### 6.2 现状事实

全链六层无缓存维度：

| 层 | 事实 | 位置 |
|---|---|---|
| DTO | `TokenUsage` 仅 prompt/completion/total 三字段——全链瓶颈 | `TokenUsage.java:12-16` |
| OpenAI 解析 | 流式末 chunk 与非流式 chat/embed/rerank 三处均只读 prompt_tokens/completion_tokens/total_tokens，未读 `prompt_tokens_details.cached_tokens` | `OpenAICompatibleProvider.java:121-147、388-396、408-418` |
| Claude 解析 | 非流式读 input/output_tokens，流式 message_start/message_delta 两段式——均未读 `cache_read_input_tokens`/`cache_creation_input_tokens` | `ClaudeProvider.java:271-279、296-313` |
| 落库 | `llm_usage_logs` 无 cached_tokens 列（V65 建表后 4 次 ALTER 均未加） | `V65__billing_wallet.sql:70-89` |
| 采集 | `UsageCollector.record` 只有两个 token 参数位 | `UsageCollector.java:137-163` |
| 计价 | `pricing_rule` 无缓存价列；textCost 只两条腿 | `PricingService.java:206-216` |
| 协议 | StreamEvent USAGE/DONE usage = {promptTokens, completionTokens, points}，无缓存位 | `StreamEvent.java:42-74`；`ChatSessionService.java:642-648` |

### 6.3 修复设计

**口径统一**（Q6=A 拍板后，关键决策——两家协议语义不同，必须先归一）：

- **cachedTokens = 缓存命中读**：OpenAI `prompt_tokens_details.cached_tokens`；Claude `cache_read_input_tokens`。
- **tokens_input = 未命中缓存的输入**：
  - OpenAI：`prompt_tokens − cached_tokens`（官方语义 cached ⊆ prompt，直接用 prompt 会双算缓存）。
  - Claude：`input_tokens + cache_creation_input_tokens`（官方语义 input_tokens 不含缓存命中；写缓存 token 计入输入侧）。
- **计价三腿**：`(tokens_input) × 输入价 + cachedTokens × 缓存价 + (tokens_output) × 输出价`。
- **缓存价**：`pricing_rule.price_cached_per_million NUMERIC(12,4) NULL`——NULL = 同输入价（未配置时行为不变，向后兼容；OpenAI 无缓存概念的旧价表零影响）。
- **Claude 写缓存溢价不建模**：`cache_creation_input_tokens` 官方计 1.25× 输入价，平台按普通输入价计（记录取舍；差异化需要时下期）。
- 上游不返回缓存字段 → cachedTokens = null（旧行为）；STATUS_ESTIMATED 估算路径缓存位落 null。

**改动面**：

1. `TokenUsage` +`cachedTokens(Long)`。
2. 两 Provider 五处解析补字段（OpenAI 流式末 chunk/非流式 chat/embed/rerank；Claude 非流式+流式 message_start）。注意 Claude 流式 cache_read 在 message_start 的 usage 里。
3. `UsageCollector.record` +cachedTokens 参数；`LlmUsageLogEntity`/表 +`cached_tokens BIGINT NULL`（存量行 null 兼容）。
4. `PricingService.textCost` 三腿 + 缓存价列读取（与 §5 闲时价正交组合：闲时取 off_peak_cached）。
5. `LlmBillingService` 结算/hold 估算链透传 cachedTokens（hold 侧缓存命中不可预知，按未命中保守预估——记录取舍）。
6. `StreamEvent` USAGE/DONE usage map +`cachedTokens`；前端聊天消耗显示「其中缓存命中 N」（DONE 校准后）。
7. 价表表单（CHAT/EMBED/RERANK）+「缓存价」输入（placeholder「留空=同输入价」）；调用明细/usage 查询 VO +cachedTokens 列。

### 6.4 数据模型

V158：`llm_usage_logs` +`cached_tokens BIGINT NULL`；`pricing_rule` +`price_cached_per_million NUMERIC(12,4) NULL`。

### 6.5 测试策略

- 单测：① 两 Provider 解析（OpenAI 带/不带 prompt_tokens_details、Claude 带/不带 cache_read/creation）；② 口径换算（OpenAI input=prompt−cached、Claude input=in+creation）；③ 三腿计价（缓存价 NULL 回退输入价）；④ 与闲时价组合矩阵。
- 人工测试：① 同一会话连发两条消息（第二条命中前缀缓存）→ usage 明细第二条 cachedTokens > 0、总积分低于无缓存同规格；② 价表不配缓存价 → 计费与改前一致（回归）；③ 聊天 DONE 消耗显示含「缓存命中 N」。

### 6.6 安全考量

缓存价是降价维度：配置错误（缓存价 > 输入价）不构成资损（多收）；上限校验「缓存价 ≤ 输入价」作软提示不硬卡。计费 fail-closed 语义不变。

---

## 7. 审计日志补齐（8x-1 / 8x-2 / 8x-3）

### 7.1 问题原文

> 1. 所有触发邮箱发送邮件的（注册+忘记密码），都没有进日志（记录IP+邮箱账号）
> 2. 还是存在英文的模块描述
> 3. 同时检查是否还存在任何操作是不会被记录到审计日志里的

### 7.2 现状事实

**机制**：@AuditLog 注解 + AOP 切面（自动采参数、LogMasker 打码、MDC 取 IP/userId）；手工建行入口 `AuditLogService.fromMdc`（web 线程）与 `recordTask`（异步线程盖戳）；`AuthService.auditAuth` 是「登录前无 JWT 时显式覆盖身份 + detail 不经 LogMasker」的现成先例（`AuthService.java:636-649`）。

**8x-1 邮件审计缺口**（P0）：

| 事实 | 位置 |
|---|---|
| `EmailVerifyController` 注释声称「审计在 EmailService 内手工建行」——**死注释，实际零代码** | `EmailVerifyController.java:36、47`；EmailService grep 'audit' 零命中 |
| 无审计端点：register/email-code、resend/email、verify/email、password/forgot、password/reset、sms/code、login/sms、wechat redirect/callback、MFA bind/bind-confirm/unbind | `EmailVerifyController.java:38-66`；`PasswordResetController.java:32-47`；`SmsAuthController.java:33-46`；`WechatAuthController.java:47-58`；`AuthController.java:64-78` |
| 字典孤儿码位已预留从未写入：email_send/email_verify/resend_email/password_forgot/password_reset/sms_code_send/sms_login/wechat_login | `AuditLabelDictionary.java:52-60` |
| IP 已就绪：发码端点显式 resolve clientIp；MDC clientIp web 线程可用 | `EmailVerifyController.java:52、63`；`MdcUserFilter.java:59-66` |
| LogMasker 会把邮箱打码 `a***@`（AOP 路径）；手工 fromMdc 行不经打码 | `LogMasker.java:29-30`；`AuditLogAspect.java:132` |

**8x-2 字典缺失**（英文残留来源）：

| 类别 | 清单 | 证据 |
|---|---|--- |
| 整模块缺失 | `security`（安全管理，5 动作）、`feedback`（公告建议台，9 动作）、`project-group`（项目组，21 动作）、`audit`（审计链，1 动作） | `SecurityEventController.java:89、116` 等 |
| 模块在字典、动作缺失（约 40 条） | auth:login_mfa/account_deleted/profile_updated；user:update_remark；asset:asset_copy；system:mail_channel_test/update_auth_channels/update_llm_model_defaults；billing:pricing_delete/payment_*（6 条）/group_reconcile_diff；media:reverse_analyze/reverse_localize；kb:kb_update/kb_grant/kb_revoke/document_*（7 条）/rag_*（10 条）/ranking_*（2 条） | 各 Controller 行号已探明（P0/P1 清单附带） |
| 前端模块下拉写死 13 项 | 缺 file/security/feedback/project-group/audit；`detailLabels.ts` 与后端也不齐（多 workflow/points/project，少上述 5 个） | `AuditLogView.vue:114-128`；`detailLabels.ts:103-109` |
| 字典注释仍写「13 模块」 | 实际 MODULE_LABEL 14 项 | `AuditLabelDictionary.java:22-37` |

**8x-3 无审计端点清单**（按重要性）：

- **P0 账号安全**：上表邮件/短信/微信/MFA 全部端点。
- **P1 管理端敏感写**：DepartmentController（CRUD+成员 5 端点）；LlmController Provider 管理（CRUD/test×3/reload，仅 export/import 有）；WorkflowController（CRUD/duplicate/import/kb-bindings）；ExecutionController 审批链（approve/reject/retry/resume/input）；AgentController（create/update/delete/copy/permissions/skills×3/sync，仅 publish 有）；UserLlmController（用户级明文密钥配置 CRUD/test）；SystemSettingController web-search/test。
- **P2 用户资源写**（本期不做，Q5）：Canvas（13 端点仅 upload 有）、Asset（16 端点仅 copy 有）、AssetProject/AssetMember/Project、FileController 下载、ChatController 会话管理、KnowledgeBase create、Payment mock/trigger。
- **P3 记忆模块写**（本期不做）：MemoryTag/Entry/GenConfig/Consolidation/ProjectRule 各写端点。
- 已覆盖确认：登录/登出/刷新/注册/注销/改资料（AuthService 手工）、支付订单（PaymentOrderService 手工）、画布上传、资产复制等。

### 7.3 修复设计（Q5=B 拍板后：P0+P1+P2+P3 一次全上）

**8x-1（P0，手工建行——公开端点无 JWT，AOP 的 MDC userId 取不到）**：

按 `auditAuth` 先例在 service 层手工建行（`fromMdc`，显式传 IP；detail 存**完整邮箱**——用户原话要「记录邮箱账号」，手工行不经 LogMasker；审计页仅 admin 可见，与 SessionService 写完整 username 同级敏感度）：

| 动作 | module:action（字典码位已在/新增） | detail | 落点 |
|---|---|---|---|
| 注册验证码发送 | auth:send_register_code（新增码） | {email, ip, result, 命中限流时 reason=RATE_LIMIT} | EmailService.sendRegisterCode 成功/失败分支 |
| 重发激活邮件 | auth:resend_email | {email, ip} | EmailService.resendVerifyEmail |
| 邮箱验证成功 | auth:email_verify | {email(从 token 解出), ip} | EmailService.verifyEmail |
| 找回密码发起 | auth:password_forgot | {identifier(用户名或邮箱原文), ip, 是否命中账号} | PasswordResetService.forgot |
| 密码重置成功 | auth:password_reset | {userId, ip} | PasswordResetService.reset |
| 短信码发送/短信登录 | auth:sms_code_send / auth:sms_login | {phone, ip} | SmsService 对应方法 |
| 微信登录 | auth:wechat_login | {openid 或 userId, ip} | WechatAuthService 回调成功分支 |
| MFA 绑定/确认/解绑 | auth:mfa_bind / mfa_bind_confirm / mfa_unbind（新增码） | {userId, ip} | MfaService 三方法 |

失败/被拒也记（result=FAIL，detail 带 reason）——审计要看到「谁在试」。

**8x-2（字典与前端对齐）**：

1. `AuditLabelDictionary` 补 4 个 MODULE_LABEL + 上述约 40 条 ACTION_LABEL（含 P1 新增注解的码）+ 注释「13 模块」改「18 模块」。
2. `AuditLogView.vue` moduleOptions 改为与后端 18 模块对齐（file/security/feedback/project-group/audit 补入；顺带核对 detailLabels.ts 的 workflow/points/project 三个幽灵码去留——后端无写入则删）。
3. `detailLabels.ts` 补 P0/P1 新 detail key（email/phone/identifier/reason/ip 等——ip 已有则跳过）。

**8x-3（P1+P2+P3 注解补齐，Q5=B 全量）**：上表 P1/P2/P3 各端点全部加 `@AuditLog(module, action)`（动作码全部同步进字典，新增约 90 条）。AOP 自动采参会把明文密钥类参数打码/截断——UserLlmController 的 key 字段确认 LogMasker 覆盖（apiKey 关键词命中打码；不命中则 detail 手工化，实现期核对）。纯读端点维持不审（§7.2 已列免审清单）。注意事项：画布/资产端点多且高频（帧写入/裁剪等编辑类操作每次都记会产生大量日志行）——编辑类低敏高频端点（frames/crop/transform/clip/concat）记审计但 detail 精简（仅 canvasId/assetId + 结果），避免审计表膨胀；若实现期发现写入 QPS 不可接受，降级为仅记 FAIL 行并回本节记录原因。

**配套**：审计页模块下拉 18 项、字典补齐、`detailLabels.ts` 对齐（8x-2 一并做，见上）。

### 7.4 数据模型

无迁移（audit 表结构不动；module/action 是字符串码）。

### 7.5 测试策略

- 单测：字典完整性测试（遍历已知全部 module:action 码断言字典命中——防再漏）；手工建行字段断言（IP/邮箱/result）。
- 人工测试：① 注册页发码 → 审计日志出现「认证-发送注册验证码」，detail 含完整邮箱+IP；② 找回密码发起/重置 → 两条日志；③ 模块下拉 18 项全中文，任选 security/project-group/feedback 模块 → 表格中文标签；④ P1 抽查：改 Provider 配置/审批工作流 → 日志在。

### 7.6 安全考量

- detail 存完整邮箱/手机号属敏感信息：审计页已有 admin 权限门 + 哈希链防篡改；权衡记录在案（用户明示要邮箱账号；掩码版已证明查不了问题）。
- 手工行绕过 LogMasker 是受控例外（仅 P0 八个动作，逐处评审）；其余全走 AOP 打码。
- 公开端点手工建行走异步池，不影响请求延迟；限流拒绝也记但**不计入** §8 的滑块失败计数（两系统独立）。

---

## 8. 邮箱验证码间隔与倒计时恢复（12x-1）

### 8.1 问题原文

> 邮箱验证码获取间隔时间是多久？如果页面刷新的话，发送按钮又立马可以点，这个时候点的话，可以再发送成功吗？还是会提醒需要按照真实的时间间隔才能再成功发送。

### 8.2 现状事实（先回答用户的提问）

- **间隔 = 60 秒**（Java 常量 `RESEND_WINDOW_SECONDS=60` 写死，`EmailService.java:45`）。
- **刷新后再点：后端会真拒绝**——Redis key `regcode:resend:<email>` 60s 窗口内计数 >1 → 抛 `RATE_LIMIT` 429「发送过于频繁，请 60 秒后再试」，**不会发第二封、不消耗每日额度**（`EmailService.java:263-271`）。所以「再发成功」不会发生。
- 但有四个偏差/恶化点：

| # | 问题 | 位置 |
|---|---|---|
| ① | 文案「60 秒」写死非真实剩余（已过 50s 只需再等 10s，提示偏保守）；429 body data 无 retryAfterSeconds | `EmailService.java:263-271`；`GlobalExceptionHandler.java:202-216` |
| ② | 前端倒计时纯内存态（ref+setInterval），无持久化、无服务端剩余秒来源——刷新/关弹窗即丢；429 后按钮不恢复倒计时可反复点 | `RegisterModal.vue:202-204、224-233、256-265` |
| ③ | **间隔内被拒也计入滑块失败数**（阈值 2、30min 窗）——刷新后连点 2 次即触发强制滑块 | `EmailService.java:244-247`；`ProgressiveCaptchaGuard.java:34-50` |
| ④ | IP 每小时配额检查在间隔检查**之前** increment——被拒也吃 IP 每小时 10 封额度；同一错误双 toast（拦截器+组件各弹一次） | `EmailService.java:261-263`；`request.ts:202-206` + `RegisterModal.vue:261-262` |

- 防轰炸已有：同 IP 每小时 10 封 + 全局日 500 封（可配 `auth.channel.mail.daily-cap`）；验证码错 5 次作废。
- 无冷却查询端点；短信同款本地倒计时实现（`SmsLoginTab.vue:116-144`），且短信「码未消费」分支返 HTTP 200 语义与邮箱不一致。

### 8.3 修复设计（Q7=A 拍板后：本地时间戳 + 429 带剩余秒，不加新端点）

1. **后端——真实剩余秒**：
   - 429 响应 data 带 `retryAfterSeconds`：间隔拒绝时读 `regcode:resend:<email>` 的 TTL 作为剩余秒（`EmailService` 抛错前 `getExpire`），错误话术改「请 N 秒后再试」（N 动态）。GlobalExceptionHandler 对 RATE_LIMIT 分支透传 data。
   - **次生修 ③**：间隔内被拒**不**计入滑块失败数（catch 分支跳过 RATE_LIMIT 的 recordFailure）。
   - **次生修 ④**：间隔检查移到 IP 每小时配额 increment **之前**（先查窗口再耗配额——被拒请求不吃额度）。
   - 间隔常量改可配（可选小项）：`auth.channel.mail.resend-interval-seconds`，默认 60——话术与配置一致。
2. **前端——倒计时恢复**（RegisterModal + SmsLoginTab 同款）：
   - 发送**成功**时 `localStorage['mailcode:cd:'+email] = 截止时间戳`（expiry = now + 60s；key 拼邮箱明文低风险，或简单 hash）。
   - 组件挂载/弹窗打开时读该 key：未到期 → 恢复倒计时（覆盖刷新场景）；到期/倒计时归零 → 清 key。
   - 收到 429 → 用 `retryAfterSeconds` 恢复倒计时并同步写 localStorage（覆盖换浏览器/清存储场景的服务端真值）。
   - **次生修 ④ 双 toast**：组件 catch 对非 40107 错误不再重复弹（拦截器已弹），只恢复倒计时。
3. **短信侧一致性**（顺带小修，非验收项）：SmsLoginTab 倒计时同款恢复；短信「码未消费返 200」分支改为与邮箱同语义 429+retryAfterSeconds（或本期仅前端兼容该 200 文案——实现期定，倾向统一 429）。

**不做**：冷却查询端点（Q7=B 弃——localStorage+429 真值已覆盖两场景）；「邮箱维度」而非「账号维度」的窗口（现按邮箱限，一人多邮箱可各发一封——注册场景本来就按邮箱走，不改）。

### 8.4 数据模型

无迁移。

### 8.5 测试策略

- 单测：① TTL 剩余秒计算与 429 data 结构；② 间隔拒绝不再进滑块失败计数；③ 配额 increment 顺序（被拒不耗额度）；④ 间隔可配读取。
- 人工测试：① 发码成功 → 刷新页面 → 按钮仍是倒计时禁用态且秒数=真实剩余；② 间隔内点发送 → 提示「请 N 秒后再试」（N=真实剩余非 60）且按钮进入倒计时；③ 连点 3 次被拒 → 不触发强制滑块；④ 短信页刷新后倒计时同样恢复。

### 8.6 安全考量

- 429 data 只带剩余秒不带邮箱存在性信息（防枚举——响应与「邮箱已注册 409」路径区分维持现状语义）。
- 修 ③④ 是收紧滥用面（被拒不耗配额不触发滑块——注意：不触发滑块放宽了刷接口面，但配额与间隔仍在，滑块本意是防机器人非罚误点；权衡记录在案）。

---

## 9. 迁移与交付清单

| 迁移 | 内容 | 服务章节 |
|---|---|---|
| V158 | ① `pricing_rule` +4 列：`off_peak_input_per_million`、`off_peak_output_per_million`、`off_peak_cached_per_million`、`price_cached_per_million`（均 NUMERIC(12,4) NULL，NULL=回退忙时/输入价） | §5、§6 |
| V158 | ② `llm_usage_logs` +`cached_tokens BIGINT NULL` | §6 |
| V158 | ③ `pricing_rule` SECOND 分辨率行合并（同 provider/model/kind/hasRef 多行取 effective_from 最新，其余逻辑删除，保留行 resolution 置 null）+ 索引重建去 resolution 段 + 迁移日志打印被合并行价清单 | §4 |
| V158 | ④ 存量 BACKSTOP 差额补进成员 used_points（按 ledger ref_id→task→提交人映射，映射不到跳过并计数） | §1 |
| — | system_settings `billing.off-peak.schedule`、`auth.channel.mail.resend-interval-seconds`：无迁移，upsert 即用 | §5、§8 |

**建议实现顺序**（风险降序）：

1. **§1 补差口径**（钱 bug，先堵）+ 存量修复
2. **§7 P0 邮件/短信/MFA 审计**（账号安全可见性，改动小见效快）+ 字典对齐
3. **§8 验证码间隔**（认证体验，独立小改）
4. **§2 对账下钻**（展示层，低风险）
5. **§4 价表去分辨率 + §5 闲时价 + §6 缓存 token**（同一改动面：价表列/表单/计价/usage，合一块做省回归）
6. **§3 实时刷新**（最大改造，WS 通道+事件发布+前端 store，放最后不阻塞前面）
7. **§7 P1+P2+P3 注解补齐**（Q5=B 全量，机械改动可穿插，量大）

每章完成同步勾销对应人工测试文件条目 + 更新 feature-map / user-ops / 速查表（7x 价表两处手册、8x 审计手册、9x 速查表 08、12x 认证手册）。

---

## 10. 测试策略总述

- 每章自带单测 + 人工测试点（上文已标），钱路径（§1）与计价路径（§5/§6）人工测试为**必过门槛**。
- 回归重点：① V155 媒体 HOLD 多退少补语义不被 §1 改动破坏（退差腿回归专项）；② 价表三改（§4/§5/§6）叠加后老价表（不配闲时/缓存价）计费结果与改前完全一致——存量兼容是硬门槛；③ §3 推送失败不影响计费正确性（DB 是真相源，推送是显示层）。
- 全部完成后按「建议实现顺序」逐条勾销 4 份人工测试文件未解决项。

## 11. 安全策略总述

- 钱改动（§1）：新 UPDATE 与既有扣款同事务；存量修复脚本幂等、宁缺勿错。
- 新入参/新配置（时段 JSON、闲时价、缓存价、间隔秒数）：格式校验 + 失败回退安全侧（回退忙时价/拒发）。
- §7 手工审计行绕过 LogMasker 是受控例外（P0 八动作逐处评审，邮箱/手机号明文仅进 admin-only 审计表）。
- §3 WS 通道复用 JWT 握手 + Origin 白名单，事件只推本人。
- 无新外部依赖、无密钥变更。

---

## 12. 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| BACKSTOP 兜底 | 组池钱不够结账时，差额记到组长个人钱包头上 | 成员花超组池 550 分，这 550 从组长个人余额扣 |
| used_points（已使用） | 成员在组里已经花掉的积分统计 | 限额 100 用了 30，used=30 |
| 对账不变量 | 一组「这几个数必须相等」的硬等式，破了就是有 bug | 组池现在的钱 = 划进来 − 花掉 + 退回 |
| 多退少补 | 预扣多了退差价、扣少了补差价 | 预扣 15 实耗 10 → 退 5 |
| est_per_resolution | TOKEN 模式视频的预检估价表（按分辨率配 ¥/秒，不参与真实扣费） | 1080p 配 1 ¥/秒 × 15 秒 = 预估 15 元 |
| SECOND/TOKEN 计价模式 | 视频按秒收费 / 按上游返回的 token 数收费 | 秒价 1 元 × 15 秒；或 89 万 token × 58 元/百万 |
| 闲时/忙时价 | 夜间等低谷时段的优惠价，不填就用正常价 | 22:00-08:00 输出价减半 |
| 缓存命中 token | 重复提问时模型不用重算的前缀部分，上游收费便宜 | 同一会话第二条消息，前文 1 万 token 命中缓存按 1 折计 |
| WS 推送（WebSocket） | 服务器有新消息主动塞给浏览器，不用浏览器反复问 | 管理员刚充值，页面积分数字立刻跳 |
| 请求作用域 SSE | 只在处理你这一次请求期间保持的流式连接，请求完就断 | 聊天回答一个字一个字往外蹦 |
| 冷却/倒计时恢复 | 发验证码后 60 秒内不让再发；刷新页面后倒计时从存的截止时间续上 | 发完码刷新页面，按钮仍显示「53 秒后可重发」 |
| retryAfterSeconds | 服务端告诉你「再等几秒」的真实数字 | 还差 10 秒就回 10，不说套话 60 |
| 孤儿码 | 字典里配了中文、但从来没有代码写入过的动作码 | 字典有「发送邮件」，日志里从没出现过这条 |
| 死注释 | 代码注释声称做了某事，实际没做 | 注释写「审计在 Service 里」，Service 里没这代码 |
| 逻辑删除 | 数据不真删，打 deleted 标记隐藏 | 价表旧行合并后标记删除，还能找回 |
