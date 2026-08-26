---
description: "子计划 E：积分变动 WebSocket 实时推送（§3，Q2=A：/ws/events + 两 wallet service 事件源 + 前端 store）"
created-date: 2026-08-26
---

# 子计划 E：积分实时刷新

> 主索引：[人工测试遗留问题修复II.plan.md](人工测试遗留问题修复II.plan.md)
> 规格：§3（7x-3，Q2=A WebSocket 推送；备选轮询留档未启用）。

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| 事件发布在计费事务内同步执行，推送慢/异常拖垮扣费 | 走 ApplicationEventPublisher + `@Async` 监听（SecurityEventPublisher 先例）：发布只是投递，监听线程推送，异常仅 WARN——DB 是真相源，推送是显示层 |
| 事务未提交事件先到，前端查到旧值 | `@TransactionalEventListener(phase=AFTER_COMMIT)` 或发布点置于事务提交后（adjust 尾部）；实现期按现有事务边界选，单测锁「提交后发布」 |
| userId→Set<Session> 并发修改 ConcurrentModificationException | CopyOnWriteArraySet 或 ConcurrentHashMap.newKeySet；推送时对每个 session try-catch（一个连接坏不炸整个循环） |
| WS 连接泄漏（前端关页未触发 close） | 服务端 30s ping、3 次未 pong 踢除；afterConnectionClosed 必清索引；前端 onbeforeunload close |
| 前端断线期间漏推 | 重连成功强制 `loadWallet()+loadGroups()` 全量补拉一次（spec §3.3 设计）；退避 1s/2s/5s/30s 封顶防风暴 |
| token 过期后 WS 变僵尸连接 | 握手校验一次+心跳保活；过期踢除后前端走既有 401 刷新流重新建连（token 刷新后 close+重连） |
| 聊天页已有 /ws/chat 连接互相干扰 | 独立端点独立 Handler 互不共享 session；前端 events 连接挂 projectGroup store，chat 连接挂 chat store |
| MyWallet/ProjectGroups 收到事件风暴（HOLD+结算连发） | 前端防抖 1s 合并重查 + `document.visibilityState` 不可见不查；徽标本身只改 store 数值（无网络）零成本 |
| 单实例假设被未来多实例打破 | 进程内事件即可；规格已记录 Redis pub/sub 为扩展点，代码注释标明——本期不做 |

## 实现步骤

- [ ] **E1：事件定义与发布点（后端）**
  - **目标**：两 wallet service 全部写方法尾部发事件
  - **动作**：
    ```
    新 billing/event/PointsChangedEvent：
        {userId, scope(PERSONAL|GROUP|MEMBER), groupId?, balanceAfter, delta, reason}
    发布点（ApplicationEventPublisher，事务提交后语义）：
        PointsWalletService.adjust（个人腿唯一咽喉——charge/refund/grant/debt/充值全过）
            → scope=PERSONAL, balanceAfter=调整后
        ProjectGroupWalletService：allocate/reclaim/chargeGroup/refundGroup/backstop
            → scope=GROUP（组池变）+ chargeGroup/backstop 兼发 MEMBER（used 变，供组页）
    发布失败（publisher 异常）catch-WARN 不影响计费返回值
    ```
  - **文件**：`billing/event/PointsChangedEvent.java`（新）、`billing/service/PointsWalletService.java`、`projectgroup/service/ProjectGroupWalletService.java`
  - **依赖**：无
  - **验证**：单测——个人 charge/grant/refund、组 allocate/chargeGroup/backstop 各发一次且字段正确（balanceAfter 数值断言）；计费返回值不受监听异常影响

- [ ] **E2：/ws/events 端点（后端）**
  - **目标**：按 userId 索引的可广播 WS 通道
  - **动作**：
    ```
    新 chat/websocket/EventsWebSocketHandler（或独立 events 包）：
        sessions = ConcurrentHashMap<Long, Set<WebSocketSession>>（newKeySet）
        afterConnectionEstablished：uid 从 attributes 取（拦截器已放）→ 索引+原注册表
        push(userId, jsonText)：遍历 Set 逐个 try { sendMessage } catch { 移除 }
        心跳：@Scheduled 每 30s pingManager.pingAll(3 次未 pong 踢除+清索引)
        afterConnectionClosed：双清
    WebSocketConfig：registry.addHandler(eventsHandler, "/ws/events")
        .addInterceptors(现有 WebSocketAuthInterceptor)——与 /ws/chat 并列同款
    ```
  - **文件**：新 Handler、`chat/config/WebSocketConfig.java`（注册行）
  - **依赖**：无
  - **验证**：单测（WebSocketHandlerTest 或集成）——同 uid 两连接都收到 push；坏连接不炸循环；close 后索引清零；无 token 握手拒绝

- [ ] **E3：监听推送（后端）**
  - **目标**：事件→WS JSON 下行
  - **动作**：
    ```
    新 billing/event/PointsChangedPushListener：
        @Async @TransactionalEventListener(AFTER_COMMIT)
        onEvent(e) → handler.push(e.userId,
            json{type:"points.changed", scope, groupId, balanceAfter, delta, reason, ts})
        异常 WARN + 丢弃计数（仿 UsageCollector 计数器风格，暴露给日志）
    ```
  - **文件**：新 Listener
  - **依赖**：E1、E2
  - **验证**：集成——charge 后测试连接收到 JSON 字段齐；AFTER_COMMIT 语义（回滚事务不发）

- [ ] **E4：前端 store 接入（徽标实时）**
  - **目标**：AppHeader 双徽标秒级响应
  - **动作**：
    ```
    stores/projectGroup.ts：
        + connectEvents()：登录态建立 new WebSocket('/ws/events?token='+auth.accessToken)
        onmessage(type=points.changed)：
            PERSONAL → personalPoints = balanceAfter
            GROUP → groups 对应组 balancePoints = balanceAfter
            同时 lastEvent.value = evt（供页面监听）
        断线重连：退避 1/2/5/30s 封顶；重连成功 → 强制 loadWallet()+loadGroups()
        onbeforeunload close；登出 close；token 刷新后 close+重连
    AppHeader.vue：onMounted 已调 pgStore.init()——增 init 内 connectEvents()（登录守卫后）
    ```
  - **文件**：`frontend/src/stores/projectGroup.ts`、`frontend/src/components/AppHeader.vue`
  - **依赖**：E3
  - **验证**：人工——A 页挂着，B 端充值/管理员发放 → A 徽标秒跳；组内 HOLD → 组池徽标秒变；断网 10s 恢复自动补齐

- [ ] **E5：页面刷新钩子（钱包/组页）**
  - **目标**：流水/列表跟随余额变
  - **动作**：
    ```
    MyWalletView.vue：watch pgStore.lastEvent（防抖 1s、visibilityState==='visible'）→ load()
    ProjectGroupsView.vue：同款 → loadGroups()（替换现状 4 处手动补刷可保留作兜底）
    ```
  - **文件**：`frontend/src/views/MyWalletView.vue`、`frontend/src/views/ProjectGroupsView.vue`
  - **依赖**：E4
  - **验证**：人工——管理员划拨后组页卡片余额自动变；切后台标签页不查（回来后徽标已新、列表手切才查可接受——或 visibilitychange 触发一次补查，实现期取简）

- [ ] **E6：铃铛轮询替换（可选，非验收项）**
  - **目标**：3s 铃铛轮询搭同一通道
  - **动作**：仅当 E2-E5 顺利且余量足——后端加 notification 事件类型，前端 Memory/Feedback 铃铛改订阅；否则保持轮询现状并在收口记录「未做」
  - **文件**：两 Badge 组件 + 相关 service 发事件
  - **依赖**：E4
  - **验证**：人工——通知到达铃铛即跳

- [ ] **E7：测试收口**
  - 规格 §3.5 单测+人工四项全过；压测轻量：模拟 50 并发连接+100 事件/秒，无泄漏无丢崩溃（本地脚本）

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 个人余额任何变动 | AppHeader 个人徽标 | 秒级更新（纯 store，无请求） | 推送失败不影响数值正确性（下次任意变动/刷新校正） |
| 组池变动 | 组徽标+组页卡片 | 秒级 | MEMBER 事件只驱动组页，徽标读 GROUP |
| 断线重连 | 全量补拉 | 重连成功 loadWallet+loadGroups | 退避封顶 30s；登出即停 |
| token 刷新 | WS 连接 | close+携新 token 重连 | 刷新失败走 401 登出流，连接自然关 |
| 页面不可见 | 列表重查 | 跳过（防抖挂起） | 徽标仍更新（store 内存操作） |
| 支付回调到账（PaymentNotify） | 用户徽标 | 秒级（经 creditRechargeForOrder→adjust→事件） | 用户多端登录全部收到（Set 索引） |

## 验证收口

- [ ] E1-E5/E7 全绿（E6 可选）；7x-3 可勾销；推送链路故障不影响计费（断 WS 全功能正常仅回到手动刷新态）
