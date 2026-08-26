# 人工测试遗留问题修复II · 功能 README

> 一轮修复五件套：A 组池补差+对账下钻 · B 审计全量补齐 · C 验证码间隔 · D 价表三改（V160）· E 积分实时推送。
> 规格唯一真相源：[../../docs/specs/人工测试遗留问题修复设计II.md](../../docs/specs/人工测试遗留问题修复设计II.md)；计划：[../../docs/plans/人工测试遗留问题修复II*.plan.md](../../docs/plans/)。

## 进度索引

| 文档 | 覆盖 |
|---|---|
| [开发进度1.md](开发进度1.md) | Plan A 全程（A1-A5，V158，含存量回填） |
| [开发进度2.md](开发进度2.md) | Plan B 全程（P0 邮件/短信/MFA 手工行→P3 注解全量+字典 18 项） |
| [开发进度3.md](开发进度3.md) | Plan C 全程（真实剩余秒/倒计时持久化/被拒不误伤） |
| [开发进度4.md](开发进度4.md) | Plan D 前半（D1-D5：价表四列/cachedTokens 三链路） |
| [开发进度5.md](开发进度5.md) | Plan D 后半（D6-D10）+ Plan E 全程（E1-E7） |

收口：单测 250/250、IT 12/12（真 PG）、vue-tsc 0。

## 用户地图（谁用/场景/效益）

| 用户 | 场景 | 效益 |
|---|---|---|
| 普通成员 | 组内生成视频结算 | 已使用积分=真实消耗（不再少记差额），组池一眼准 |
| 组长 | 组池不足时结算 | 差额自动走组长补差腿，账单/流水/used 三处对齐 |
| 全体用户 | 钱包/组页/顶栏徽标 | 积分变动**秒级刷新**，不用手动 F5 |
| 注册/找回用户 | 收验证码 | 刷新页面倒计时仍在；间隔提示显**真实剩余秒**；误点不触发滑块 |
| 管理员（账单） | 项目组对账 tab | 按组下钻筛选+显示全部组，顶卡合计跟随 |
| 管理员（价表） | 配 Cdance2.0/LLM 价 | 无分辨率档（只有有/无参考两行）；LLM 支持闲时输入/输出/缓存价+闲时时段（分周末）；est 偏差 tag 提示校准 |
| 管理员（审计） | 审计日志页 | 发码/找回密码/MFA 全有日志（含邮箱+IP）；模块下拉 18 项全中文；P1/P2/P3 操作全量留痕 |
| 智能对话用户 | 同会话追问 | 缓存命中部分按缓存价计费，第二轮更便宜；DONE 显「缓存命中 N」 |

## 技术说明（速览，详见各进度）

- **补差**：结算腿组池不足 → 差额无条件计入成员 used（quota 不卡）+ 组长个人扣款/挂 DEBT，同事务；V158 存量回填（映射不到跳过计数）。
- **对账下钻**：`/api/billing/admin/group-reconciliation` 扩 `groupId`/`includeAll` 参，只读、权限不变。
- **审计**：P0 八动作服务层手工建行（无 AOP 上下文），P1-P3 `@AuditLog` 注解补齐；模块字典前后端单一来源（18 项）。
- **验证码**：429 data 带 `retryAfterSeconds`（Redis TTL 真实剩余）；前端倒计时 localStorage 持久化（按邮箱 key）；间隔拒绝不进滑块计数、不耗 IP 配额。
- **价表三改（V160）**：`pricing_rule` 行身份去 resolution（有/无参考两槽）；+4 价列（闲时入/出/缓存、缓存价，NULL=回落）；闲时时段 system_settings `billing.off-peak.schedule`（读宽容/写严格/Asia/Shanghai）；`llm_usage_logs.cached_tokens` 落库→计费三腿→前端「缓存命中 N」。
- **实时推送**：两 wallet 写咽喉发 `PointsChangedEvent` → `AFTER_COMMIT @Async` 监听 → `/ws/events`（uid→Set 多端）→ 前端 store 徽标秒级 + 页面 watch lastEvent 防抖刷新；断线退避重连全量补拉。DB 是真相源，推送失败仅 WARN 不影响计费。

## 关联产物

- 测试方案：[../../docs/测试方案/人工测试遗留问题修复II测试方案_积分计费实时.md](../../docs/测试方案/人工测试遗留问题修复II测试方案_积分计费实时.md)、[…_审计验证码.md](../../docs/测试方案/人工测试遗留问题修复II测试方案_审计验证码.md)
- Feature Map：[../../docs/feature-map/人工测试遗留问题修复II.feature-map.md](../../docs/feature-map/人工测试遗留问题修复II.feature-map.md)
- 用户手册：[../../docs/user-ops/人工测试遗留问题修复II用户操作手册.md](../../docs/user-ops/人工测试遗留问题修复II用户操作手册.md)
