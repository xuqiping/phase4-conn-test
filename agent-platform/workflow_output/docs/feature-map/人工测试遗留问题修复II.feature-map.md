# Feature Map · 人工测试遗留问题修复II

> 五子计划 A-E 代码速查。建表走 Flyway V158/V160（改 `pricing_rule`/`llm_usage_logs` + 存量回填，无新表）。原理大白话注解见文末。

## 一句话描述

修五件人工测试遗留：组池结算差额口径（少记）+ 对账按组下钻、审计日志全量补齐+中文化、验证码间隔真实剩余+倒计时持久化、价表去分辨率/闲时价/缓存价三改、积分变动 WebSocket 秒级推送。

## 代码位置总览

| 类型 | 路径 | 作用 |
|---|---|---|
| 迁移 | `db/migration/V158__*.sql` | pricing_rule+4 价列、llm_usage_logs+cached_tokens、BACKSTOP 存量回填 used |
| 迁移 | `db/migration/V160__*.sql` | SECOND 分辨率行合并（留最新，resolution 置 null）+索引重建 |
| Service | `projectgroup/service/ProjectGroupWalletService.java` | A：结算差额腿（组长补差+无条件加 used）；E：组事件发布 |
| Service | `billing/service/PointsWalletService.java` | E：adjust 尾部个人事件唯一咽喉 |
| Service | `billing/service/PricingService.java` | D：三腿计价（输入/输出/缓存）× 闲时 × legacy 回落 |
| Service | `billing/service/PricingConfigService.java` | D：pricingIdentity 三维判重/EST 槽位/est 偏差查询 |
| Service | `system/service/SystemSettingService.java` | D8：闲时时段读写（读宽容/写严格） |
| Service | `auth/service/EmailService.java` | C：429 带 retryAfterSeconds；间隔检查先于配额 increment |
| 事件 | `billing/event/PointsChangedEvent.java` | E1：scope=PERSONAL/GROUP/MEMBER |
| 事件 | `billing/event/PointsChangedPushListener.java` | E3：AFTER_COMMIT+@Async → WS JSON |
| WS | `chat/websocket/EventsWebSocketHandler.java` | E2：uid→Set<Session> 广播+ping+stats |
| WS | `chat/config/WebSocketConfig.java` | 注册 `/ws/events`（同 /ws/chat 拦截器+Origin 白名单） |
| Controller | `billing/controller/BillingAdminController.java` | A2：对账接口 groupId/includeAll 参数 |
| Controller | `billing/controller/PricingConfigController.java` | D：导出忽略 resolution/est-deviation 端点 |
| 审计 | `audit/**`（AuditLog 注解+aspect+字典） | B：P1-P3 注解补齐、模块字典 18 项 |
| 前端 store | `stores/projectGroup.ts` | E4：connectEvents/lastEvent/退避重连/全量补拉 |
| 前端页面 | `views/admin/BillingAdminView.vue` | A2：对账 tab 组筛选+includeAll 开关 |
| 前端页面 | `views/admin/PricingConfigView.vue` | D：四价输入/无分辨率/闲时缓存摘要/est 偏差 tag |
| 前端组件 | `components/settings/BillingSettingsTab.vue` | D8：闲时段卡（HH:mm、≤4 窗、周末分栏） |
| 前端组件 | `RegisterModal.vue`/`ForgotPasswordModal.vue`/`SmsLoginTab.vue` | C：倒计时 localStorage 持久化+真实剩余 |
| 前端页面 | `views/MyWalletView.vue`/`ProjectGroupsView.vue` | E5：watch lastEvent 防抖刷新 |
| 测试 | `PricingServiceTest`（D10 矩阵）、`PointsWalletServiceTest`、`ProjectGroupWalletServiceIT`、`EventsWebSocketHandlerTest`、`SystemSettingServiceTest`、`EmailServiceTest` | 250 单测+12 IT |

## 调用链路（E 实时推送关键路径）

```
计费写库（两 wallet service 写方法尾）
  → ApplicationEventPublisher.publish(PointsChangedEvent)
  → 事务 COMMIT 后 @Async PointsChangedPushListener
  → EventsWebSocketHandler.push(userId, json) → 该用户全部在线连接
  → 前端 projectGroup store onmessage → 徽标秒更（纯内存）+ lastEvent
  → 页面 watch lastEvent（防抖 1s、可见才查）→ 列表刷新
```

## 关键文件详解（后端挑核心）

| 文件 | 说明 | 重点关注 |
|---|---|---|
| ProjectGroupWalletService | 组结算：组池不足差额→组长腿+used 无条件加 | 差额腿与组长扣款同事务；发布 catch-WARN |
| PricingService | computeCost 三腿+闲时+legacy NULL 回落矩阵 | NULL=回落是硬门槛（D10 锁死） |
| EventsWebSocketHandler | ConcurrentWebSocketSessionDecorator(5s,256KB) 串行化+慢连接剔除 | close/transportError 双清索引 |
| SystemSettingService | off-peak 读损坏→disabled（宁多收）写非法→拒 | 时区强制 Asia/Shanghai |
| EmailService | 间隔检查→配额 increment 顺序；429 data retryAfterSeconds | 被拒不进滑块计数 |

## 原理大白话

- **HOLD/补差**：先按预估「押金」冻结，结算多退少补；组池不够付的部分由组长兜底——成员的 used 是统计列，永远记真实消耗，不受 quota 卡。
- **AFTER_COMMIT 事件**：DB 事务没提交就推送，前端会查到旧值白推一次；提交后才推，回滚则啥也不发。发布/推送全程 catch——账是对的，推送只是锦上添花。
- **uid→Set<Session>**：一人多端登录（手机+电脑）都收；「每连接一个快递员，慢的（5s 发不出/超 256KB）直接辞退」防一个坏连接拖死广播循环。
- **闲时段读宽容写严格**：读配置坏了当「没有闲时」（多收点用户可接受，少收不可接受）；写配置非法直接拒保存（不让坏数据入库）。
- **缓存价 NULL 回落**：不填=没这功能，行为与改前逐分一致——老价表零迁移成本（D10 矩阵六 kind 锁死）。
- **倒计时 localStorage**：把「还能再发的时间点」存浏览器；刷新后拿当前时间一减就是真实剩余，后端 429 的 TTL 是最终裁判（换浏览器也骗不过）。

## 踩坑批注

- quoted 列别名（`AS "providerId"`）才进 MyBatis 驼峰映射（D9 est 偏差 SQL）。
- `@TransactionalEventListener` 默认无事务时不执行——IT 级验证回滚不发。
- pom 默认 `surefire.excludedGroups=integration`：跑 IT 需 `-Dsurefire.excludedGroups=`。
- WS decorator 的 `getId()` 需 delegate，否则按 id 移除失配泄漏连接。
