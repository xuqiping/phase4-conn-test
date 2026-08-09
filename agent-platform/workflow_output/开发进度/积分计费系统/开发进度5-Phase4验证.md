# 开发进度5 · 积分计费系统 Phase4 真 E2E 冒烟验证（2026-08-07）

承接 [开发进度4](开发进度4.md)。Phase4：起真实 backend/frontend/PG/Redis，配虚拟价 → 充值 → 发对话 → 验扣费/流水/usage_log → 失败/不足 → admin/user 分权。Playwright MCP 浏览器 + API/DB 交叉验证。

## 环境

- backend 8080（mvn spring-boot:run，Flyway 自动跑 **V65/V66 建表成功**，6 表齐）、frontend 5174（新 billing 代码）、PG 16、Redis。
- 账号：admin/admin123（id=1，含 pricing:manage/points:recharge/usage:view）、vis_test（id=3，复用 admin 哈希设 admin123）、newuser（id=4，0 余额）。
- provider：deepseek/glm/kimi/doubao(CHAT)、doubao-embedding(EMBED)、seedance Cdance2.0(VIDEO)。

## 虚拟价配置（API 建 + UI 渲染验证）

- **价表 7 条**（kind+model 精确匹配，无 model 通配）：CHAT 5 模型(deepseek-chat/glm-5.1/k2.6/doubao-seed-2.0-code/doubao-seed-2.1-code) ¥1/M in + ¥2/M out、EMBED doubao-embedding-vision ¥0.1/M、VIDEO Cdance2.0 TOKEN ¥10/M。
- **阶梯比例 1 档**：[0,∞)=1000 pt/¥（多档增量建不可行，见 finding F2）。
- UI 价表配置页渲染 7 行（kind→中文「文本对话/向量嵌入/视频生成」、providerId→全局、价/生效时间/编辑）+ 阶梯表 [0,∞)=1000（编辑/删除）。截图 `billing-01-pricing-config.png`。

## 验证矩阵

| # | 路径 | 结果 | 证据 |
|---|---|---|---|
| V1 | 配价表+阶梯 | ✅ | API 200 + UI 7 行渲染 |
| V2 | admin 充值（修 B1 后） | ✅ 三表一致 | balance 50000 + ledger ADMIN_GRANT +50 + order PAID/¥0/ADMIN |
| V3 | 发对话(glm-5.1,496/36 token)→扣费 | ✅ 数学一致 | cost¥0.000568×1000=0.57pt；balance 100000→99999.43；usage_log SUCCESS(provider_id=4,GLOBAL) + ledger CONSUME -0.57 |
| V4 | 失败调用(doubao provider 404)→不扣 | ✅ | usage_log FAILED/0 token/0 cost；balance 不变；无 CONSUME 流水 |
| V5 | 余额不足(0)→预检拦 | ✅ | requireAffordable 在 LLM 调用前抛 code=40201「积分余额不足」 |
| V6 | admin 见真 token/¥ | ✅ | overview: in496/¥0.000568/0.57pt/调用17 + by-model/kind/user(="1" CAST TEXT)/trend 全聚合；截图 `billing-02-admin-overview.png` |
| V7 | 用户只见积分(无 token/¥) | ✅ | /me/wallet + /me/usage VO 刻意无 token/¥ 列；截图 `billing-03-my-wallet.png` |
| V8 | refund 路径 | ⚠️ 单测覆盖 | LLM 路径成功后才扣→失败本就没扣无退；VIDEO charge→markSucceeded 失败退由 MediaBillingServiceTest(6 绿)+SeedDance 任务 E2E 覆盖 |

**正向扣费数学核对**（glm-5.1）：`(496/1e6×1 + 36/1e6×2) = ¥0.000568` × `1000 pt/¥ = 0.568 → 0.57pt`。usage_log.cost_yuan/points_consumed 与计算逐位一致。

## 🔴 修复的 Bug（本轮 Phase4 run→fix→rerun）

- **B1（blocker，充值全失效，已修）**：`WalletAdminController.recharge` 传 `moneyYuan=null`，但 `payment_order.amount_yuan` NOT NULL(V65) → 每次充值违反非空 → grant 整事务回滚 → **admin 充值端点 100% 失败**。根因日志：`null value in column "amount_yuan" of relation "payment_order" violates not-null constraint`。修：`PointsWalletService.grant` 兜底 `amountYuan != null ? moneyYuan : BigDecimal.ZERO`（admin 纯发放记 ¥0）。修后充值三表一致。
- **B2（错误信息误标，已修）**：`GlobalExceptionHandler.handleDuplicateKey` 捕获所有 `DataIntegrityViolationException`（含 NOT NULL 违例），正则 `"([^"]+)"` 抓首引号串当作「约束名」→ NOT NULL 违例被报成「唯一约束冲突：amount_yuan」（把列名当约束名，误导排查）。修：先判 root 含 `null value`/`not-null` → 走「必填字段为空（列名）」400 分支；否则才走唯一冲突 409。
- **B3（KPI 卡漏输出 Token，已修）**：账单总览顶部 KPI 卡仅 4 张（调用次数/输入Token/真实金额¥/消耗积分），漏「输出 Token」——后端 `UsageOverviewVO.totalTokensOutput`(=36) API 实返、by-kind/by-model 表格也有「输出 Token」列，唯顶部概览卡漏渲染（用户问「为什么只有输入 token」即此）。修：`BillingAdminView.vue` 顶部 `n-grid :cols="4"`→`5`，在「输入 Token」后补 `totalTokensOutput` 卡。重载验 5 卡齐：调用次数17 / 输入496 / 输出36 / ¥0.0006 / 积分0.57。

## ⚠️ 记档 Finding（未修，建议后续）

- **F1（UI/后端矛盾）**：`PricingConfigView` model 占位符「模型名（全局价可空）」，但 `PricingConfigService` 校验 `model 不能为空`(line 81)——UI 让你留空，保存 400。且 `findEffective` SQL 按 `kind+model` 精确匹配（model 非通配），故本就无「全局价=model 空」语义。建议：改占位符为「模型名（必填，须与 provider models 一致）」。
- **F2（易用性）**：阶梯比例增量建多档不可行——`validateTierContinuity` 在每次 insert 后跑，要求「首档 min=0 + 末档 max=∞ + 相邻连续」，任何中间态非法（单条有 max=「末档须∞」；首条 min≠0 拒）。只能建 [0,∞) 单档。要多档须加「批量建/整体替换」端点或前端一次性提交。
- **F3（minor）**：`ChatRequest.model` 在 `agentId` 在场时被忽略（agent 路由强制自家 model）。非计费 bug，但 `model` 字段语义不符预期。
- **F4（minor，HTTP 语义）**：`INSUFFICIENT_POINTS` code=40201 经 `resolveHttpStatus` 未命中 402xx → 落到 HTTP 500。「余额不足」应 4xx（402/400）而非 500（服务端错误）。建议 resolveHttpStatus 加 `40200-40299 → 400/402` 映射。
- **F5（provider 配置，非计费）**：doubao chat endpoint `https://ark.cn-beijing.volces.com/api/coding/chat/completions` 返 404（`/api/coding/` 路径疑误配）。预存，非本轮计费引入。

## 测试

- **billing 单测 54/54 全绿**（PointsWalletServiceTest 12 含 grant/B1 修复点全过；PricingConfigServiceTest 8；PricingService 7；LlmBillingService 5；MediaBillingService 6；BillingQueryService 6；UsageCollector 4；BillingContext 6）。
- **全量 `mvn test` 945 用例：1 failure + 2 errors，均为开发进度4 已记录的 3 处预存失败**（`AuthServiceTest.login_success`/`RagRetrievalServiceTest.grayZone`/`RuntimeCallbackSecurityTest` sidecar 401，clean tree 已验非本次）→ **B2 改 GlobalExceptionHandler 触达全局，零新增回归**。

## 沉淀

- **价表匹配是 `kind+model` 精确，无 model 通配**：配价须按真实模型名逐条建；「全局价」= provider_id NULL（model 仍须具体）。admin UI 占位符与后端校验须对齐（F1）。
- **NOT NULL 违例 ≠ 唯一冲突**：异常 handler 须按 PG message 分支（`null value`/`duplicate key`），正则提约束名仅适用 duplicate。凡全局异常 handler 照此。

## 下一步

- 上文 finding F1-F4 建议排期修（F1 改文案/F2 批量阶梯端点/F4 HTTP 映射为佳）。
- VIDEO 真计费 E2E（Cdance2.0 真任务→chargeMedia→扣→流水 kind=VIDEO）可随 SeedDance 下次真任务联调一并验（本轮 provider chat 路径 doubao 404，video 独立 worker 不受影响，单测已覆盖）。
- Chunk K（用户自助支付网关）= Phase2，本轮不实现。

## 增量特性 · 账单总览「调用明细」Tab（Phase4 后续，用户提）

**起因**：用户指出账单总览全是聚合（KPI 求和 + 日趋势/用户/模型/类型排行），看不到「哪个用户、何时、调哪个模型、进出 token、花多少、成功失败」的逐条明细——而 `llm_usage_logs` 表每条记录本就有 `created_at/user_id/model/tokens_*/cost_yuan/points_consumed/status/error_msg`。

**实现**（纯新增，1 端点 + 1 tab，零回归）：
- 后端 `GET /api/billing/admin/call-log`（`usage:view`），分页 + 按 用户/模型/类型/状态 筛选 + 日期区间（复用 `clamp` 30 天默认/365 封顶）。镜像 `RagRetrievalLogController.page` 范式。
- `LlmUsageLogMapper` 加 `countDetail`/`pageDetail`（XML，`LEFT JOIN users` 取 username/name → VO 同返 `username`+`displayName`，user_id 可空系统调用不丢行）。新 DTO `UsageDetailVO`。
- `BillingQueryService.pageDetail`：clamp + size 封顶(默认20/上限100) + total==0 短路 + `PageResult.of`。
- 前端 `billing.ts` 加 `UsageDetailVO`/`UsageDetailQuery`/`listUsageDetail` + `USAGE_STATUS_LABEL`/`USAGE_STATUS_TAG_TYPE` maps。
- 前端 `BillingAdminView.vue` 加第 5 tab「调用明细」：筛选条(用户/类型/状态 n-select + 模型名回车 n-input) + `n-data-table remote` 服务端分页(20/50/100)，列 时间/用户/模型/类型(tag)/输入/输出/¥/积分/状态(tag)/错误。独立 `loadDetail`（不进 `loadAll` 的 Promise.all），tab 首次激活懒加载 + 筛选 watch 回 page1。用户下拉 options 来自 `adminApi.listUsers`（403 容错空下拉）。

**验证**：
- 后端 `BillingQueryServiceTest` +3 用例（返 PageResult/size 封顶+offset 算/total==0 短路）→ 9/9 绿。
- 真 API：admin token 调 `/admin/call-log` → `total=51`、`kind=VIDEO`→1(Cdance2.0 873 token/¥0.873/873pt)、`status=FAILED&model=doubao-seed-2.0-code`→47、page2 size2 offset 对(id 降序)、vis_test 无 `usage:view`→403。
- 前端 vue-tsc 净 + vitest 247/247。
- Playwright E2E：`/admin/billing` 点「调用明细」→ 10 列表格渲染（Cdance2.0 视频成功/doubao 404 失败带错误/k2.6 577-98 成功/glm-5.1 0-31 成功），用户列显 admin（JOIN），状态/类型中文 tag，分页 1/2/3 + 20/页。模型名输 `k2.6`+回车→表剩 1 行（前端筛选→重载链路通），用户下拉 options 齐（徐启平/newuser/vis_test/aa64221886/admin）。

