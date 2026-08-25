---
description: "子计划 B：V157 迁移 + 聊天 PROGRESS/usage + HOLD + SSE 回退 + DEBT 兜底（规格 §4）"
created-date: 2026-08-25
---

# 子计划 B：聊天计费闭环

> 主索引：[人工测试遗留问题修复.plan.md](人工测试遗留问题修复.plan.md)
> 规格：§4（Q2=B / Q3=B / Q4=B / Q10=A 拍板）

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| SSE 裸线程：BillingContext/SecurityContext 手工 set/clear | PROGRESS 定时器回调里**不读 ThreadLocal**，所需 userId/价格在流装配前捕获为局部变量 |
| PROGRESS 定时器泄漏（流结束/取消后仍发射） | `Flux.doOnNext` 累计 + `takeUntil`/finally 显式 dispose；发送包 try-catch，失败只记日志 |
| HOLD 全额冻（Q4=B）导致正常用户开局被拒 | 话术必须带数：「预估上限 X，可用 Y，请先充值或换小 max_tokens」；开关 `billing.chat-hold.enabled` 可一键回退现状 |
| messageId 幂等：hold/settle/refund 三腿 + SSE 重连 | 幂等键 `chat-hold-{messageId}` / `chat-settle-{messageId}`；settle 先查 hold 腿是否存在 |
| 取消时 usage 有无不确定 | 优先 provider 精确 usage；无则字符折算（Q3=B）；折算成本 > hold 时按 hold 封顶 |
| DEBT 并发双结算竞态（两路同时扣到 0） | 新 mapper 方法内 `SELECT ... FOR UPDATE` 钱包行 → 再算扣到 0/记 debt；与充值冲抵同锁 |
| 充值回调与管理员发放两处 credit 都要冲抵 debt | 冲抵逻辑放 walletService.credit 入口层统一做，不散落回调里 |
| 关闭 hold 开关后 DEBT 腿仍需工作 | 两开关独立；DEBT 只在结算失败分支触发，与 hold 与否无关 |

## 实现步骤

- [ ] **B1：V157 迁移 + 实体**
  - **目标**：`users.remark`（子计划 D 用）+ `user_points_balance.debt_points`
  - **动作**：`V157__user_remark_and_debt.sql`：
    ```
    ALTER TABLE users ADD COLUMN remark VARCHAR(128) NULL;
    ALTER TABLE user_points_balance ADD COLUMN debt_points NUMERIC(18,2) NOT NULL DEFAULT 0;
    ```
    `User.java` 加 remark；`UserPointsBalanceEntity` 加 debtPoints（顺手修 §1.3 的「可负」陈旧注释）
  - **文件**：`db/migration/V157__*.sql`、`auth/entity/User.java`、`billing/entity/UserPointsBalanceEntity.java`
  - **验证**：flyway migrate 本地通过；存量行 debt_points=0

- [ ] **B2：StreamEvent PROGRESS + DONE.usage（Q2=B）**
  - **目标**：流中实时报数、流尾精确值
  - **动作**：
    - `StreamEvent` 增工厂：`progress(estimatedTokens, estimatedPoints)`、`done(usage)`（usage 可空，老客户端忽略未知字段）
    - 流装配处（ChatController/ChatSessionService）：局部计数器累计 chunk 字符；每 ≥1000ms 或每 32 chunk 发一条 PROGRESS（系数 `billing.chat.char-per-token`，默认 1 token≈1.6 字符）
    - usage sink（doOnComplete 已有）：把 usage 传入 DONE 构造（替换现 `StreamEvent.done()` 空载调用，两处：正常尾 + 兜底尾）
  - **文件**：`chat/dto/StreamEvent.java`、`chat/controller/ChatController.java`、`chat/service/ChatSessionService.java`（Phase 3 定位流装配点）
  - **验证**：curl SSE 手测——流中见 PROGRESS 递增、DONE 带 usage；取消流无定时器泄漏（日志无迟发）

- [ ] **B3：聊天 HOLD（Q4=B 无帽）+ 结算 + 取消折算（Q3=B）**
  - **目标**：并行管控，开局冻 min(maxTokens×出价单价, 可用)
  - **动作**：
    - 开关 `billing.chat-hold.enabled`（默认 true）读 system_settings
    - 入口（消息落库后、发流前，个人与组两模式）：
      ```
      est = prompt估算tokens(历史+本轮字符÷系数) × 入价 + maxTokens(请求参数→Provider配置→系统兜底) × 出价
      hold = est 全额                      // 不做 min(可用) 截断——截断则 hold 永不失败、开局拦截失效
      if 可用[个人钱包 或 组池+双卡] < est: 抛 INSUFFICIENT_POINTS(带 est 与可用数)   // 走 B4 话术通路
      chargeIdempotent/chargeGroup(userId, hold, "CHAT-HOLD", messageId, "chat-hold-{messageId}")
      ```
    - 正常尾：settle = usage 精确成本；差 = hold−settle，正数退差 REFUND、负数补扣 CONSUME（组模式 BACKSTOP 链保留）
    - 取消/中断：usage 有 → 精确；无 → 折算 `min(字符数/系数×单价, hold)`；settle=0 才全额退
    - 画布 LLM 非流式（CanvasNodeRunnerService）同规则接入（同一 service 方法，勿只改 SSE 一条路）
  - **文件**：`chat/service/ChatSessionService.java`、`chat/service/LlmBillingService.java`（或新 ChatHoldService）、`canvas/.../CanvasNodeRunnerService.java`、`billing/service/PointsWalletService.java`
  - **依赖**：B2（DONE.usage 供 settle）
  - **安全检查**：幂等键全覆盖；开关可回退
  - **验证**：单测（hold 失败拒/多退少补/取消折算/重复 settle 幂等）；人工：余额 1 发起 → 明确「积分不足」话术

- [ ] **B4：SSE 回退修复**
  - **目标**：积分不足话术到前端；杜绝双答
  - **动作**：ChatController.java:315 catch 分流：
    ```
    if e 是 BusinessException 且 未发过 chunk → emitter.send(ERROR(e.userMessage)) + DONE + complete; return
    if 已发过 chunk → emitter.send(ERROR("回答中断")) + DONE + complete; return   // 不重答
    else → 现状同步回退（保留）
    ```
    「已发 chunk」用现有发射回调置 flag（参照 sentDone 模式）
  - **文件**：`chat/controller/ChatController.java`
  - **依赖**：无
  - **验证**：人工——余额不足 SSE 发起收到业务话术；流中途 kill 后端不重答

- [ ] **B5：DEBT 兜底（Q10=A）**
  - **目标**：没拦住也扣——扣到 0 + 挂账 + 充值先还
  - **动作**：
    - 开关 `billing.debt-collect.enabled`（默认 true）
    - `UserPointsBalanceMapper` 新方法 `deductToZeroAndDebt(userId, cost)`：
      ```
      SELECT 行 FOR UPDATE
      if balance >= cost: 走既有条件 UPDATE（正常路径不进此方法）
      else: pay = balance; UPDATE SET balance=0, debt=debt+(cost−pay); 记 CONSUME(pay)+DEBT(cost−pay) 两腿流水
      ```
    - 结算失败分支接入（LlmBillingService 吞异常处、媒体补扣差额处、组内 BACKSTOP 后组长个人仍不足处→组长 DEBT）：开关开 → 走 deductToZeroAndDebt；关 → 维持现状吞异常
    - 入口拦截：`requireAffordable`（:66-75）加 `debt_points > 0 → 拒（话术「有未偿还欠款 X，请充值后自动偿还」）`
    - 冲抵：`walletService.credit`（充值回调+管理员发放统一入口）开头：`if debt>0: 还款=min(credit, debt); debt−=还款; 记 DEBT_REPAY; credit−=还款`，余数进余额
    - 前端：MyWalletView 欠款行「欠款 X（充值后自动偿还）」；充值页同提示
  - **文件**：`billing/mapper/UserPointsBalanceMapper.java`、`billing/service/PointsWalletService.java`、`billing/service/LlmBillingService.java`、`billing/service/MediaBillingService.java`（补扣差额分支）、`frontend/src/views/MyWalletView.vue`、充值相关 view
  - **依赖**：B1
  - **安全检查**：DEBT/DEBT_REPAY 全流水留痕；行锁防并发双扣
  - **验证**：单测（扣到0/挂账/充值冲抵/发放冲抵/并发）；人工：余额 10 构造 300 结算 → 余额 0 欠 290 → 消费全拒 → 充值 100 → 欠 190 余额 0

- [ ] **B6：测试与实测**
  - 人工（必过门槛）：并行画布 LLM + 智能对话各自 hold/结算；流中 PROGRESS 跳动 DONE 校准；SSE 话术；DEBT 全链

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| DONE.usage 到达 | 聊天回答尾消耗条 | 显示精确值 | 老消息无 usage 显示「-」；ERROR 后不显示 |
| PROGRESS 到达 | 计数跳动 | 递增 | ERROR/DONE 后停止接收 |
| hold 失败 | 前端话术 | 积分不足+预估/可用数 | 开关关时不出现（回退现状预检>0） |
| DEBT 产生 | 钱包页欠款行 / 全部消费入口 | 显示+拦截 | 管理员发放也先冲抵；debt=0 自动解除 |
| 充值到账 | 欠款减少 | 先还后进余额 | 部分还款=欠款减、余额 0 |

## 验证收口

- [ ] B1-B6 全绿；两开关 on/off 四象限冒烟（hold×debt）
