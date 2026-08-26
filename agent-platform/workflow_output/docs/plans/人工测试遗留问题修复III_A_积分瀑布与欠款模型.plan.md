# Chunk A · 积分瀑布与欠款模型（后端核心）

> 规格 §3（账务模型）/§4.0 通用 /§7。修复缺陷1（组池未消耗）+ 缺陷2（成员欠款）。B 的前置。

## A1. Flyway V161 迁移

- 目标：成员行三新列 + 存量 used>quota 拆分回填。
- 文件：`db/migration/V161__group_member_self_and_debt.sql`（新增）
- 伪代码：
  ```
  ALTER project_group_members ADD self_points NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(>=0)
  ADD debt_pool_points NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(>=0)
  ADD debt_leader_points NUMERIC(12,2) NOT NULL DEFAULT 0 CHECK(>=0)
  -- 回填（幂等：used<=quota 或 debt 已 >0 的行跳过）
  FOR 受影响行 (used>quota AND debt合计=0):
    overflow = used - quota
    leaderShare = 组内 ΣBACKSTOP / (ΣCONSUME+ΣBACKSTOP)   -- 组级比例近似
    debt_leader = round(overflow * leaderShare)
    debt_pool   = overflow - debt_leader
    UPDATE 行 SET used=quota, debt_pool=…, debt_leader=…, remark 无（迁移留注释）
  ```
- 验证：本地执行后 SELECT 抽样行 used<=quota 且 debt 合计=原溢出；重复执行无变化（幂等）。迁移前先 COUNT 受影响行（>1000 分批）。
- 依赖：无。

## A2. Mapper 层新语句

- 文件：`projectgroup/mapper/ProjectGroupMemberMapper.java`、`ProjectGroupWalletMapper.java`
- 伪代码：
  ```
  memberMapper:
    selectForUpdate(已有 selectByGroupUserForUpdate 复用)
    deductSelf(groupId,userId,x): UPDATE self_points=self_points-x WHERE self_points>=x   -- 条件扣名下
    creditSelf(groupId,userId,x): self_points+self_points+x
    addUsedRaw(=addUsedUnconditional 改名/复用): used+x 无守卫
    addDebtPool / addDebtLeader(groupId,userId,x)
    repayDebt(…): 见 B                              -- A 只加列操作，B 补还款复合
  walletMapper:
    deductUpTo(groupId,x): UPDATE balance=balance-x WHERE balance>0;
                           返回实际扣减 = min(x, 扣前balance)  -- 需 RETURNING 或先 SELECT FOR UPDATE 取值
    （实现取「selectByGroupIdForUpdate 读余额 → 计算 actual → 条件 deduct(actual)」，避免 RETURNING 方言问题）
  ```
- 验证：单测条件边界（余额 0/x/超 x 三档）。
- 依赖：A1。

## A3. chargeGroup 瀑布重构（缺陷1 根除）

- 文件：`projectgroup/service/ProjectGroupWalletService.java`（doChargeGroup 重写）、调用方 `billing/service/MediaBillingService.java`、`billing/service/LlmBillingService.java`（签名透传）
- 伪代码：
  ```
  chargeGroup(gid, member, cost, refType, refId, idemKey, allowDebt:boolean):
    doChargeGroup:
      enforceKindAllowed
      memberRow = selectByGroupUserForUpdate            -- 恒锁成员行（读 quota/used/self/debt）
      manager 硬卡（allocatable，V156 逻辑保留，仅 HOLD/allowDebt=false 时卡）
      if !allowDebt:                                    -- HOLD/提交侧
        可用 = debt总>0 ? 0 : (quota==null ? ∞ : quota-used)
        if cost > 可用: throw 超限额（含欠款冻结话术）
      -- 资金瀑布（各腿独立，业务不足不抛、转下腿）
      poolPart = min(组池余额, cost);   组池 deduct(poolPart)
      selfPart = min(self_points, cost-poolPart);  deductSelf(selfPart)
      shortfall = cost - poolPart - selfPart
      if shortfall>0: pointsWallet.charge/chargeToDebt(组长, shortfall)   -- 既有 B5 口径
      -- 成员记账（无条件）
      usedBefore = used; addUsedRaw(cost)
      overflow = quota==null ? 0 : max(0, usedBefore+cost-quota)
      if overflow>0:                                     -- 尾部归因（瀑布倒序）
        leaderTail = min(overflow, shortfall)
        rem = overflow - leaderTail
        selfTail  = min(rem, selfPart)                   -- 自己的钱垫的超帽段，不记欠款
        poolTail  = rem - selfTail
        addDebtLeader(leaderTail); addDebtPool(poolTail)
      落腿（>0 才落）：CONSUME(-poolPart) / SELF_CONSUME(-selfPart) / BACKSTOP(-shortfall)
      publishGroupChanged 一次（聚合 delta=-cost）; publishMemberUsed 一次
  调用方改造：
    MediaBillingService.holdMediaEstimated → chargeGroup(..., allowDebt=false)
    MediaBillingService.settleMediaSuccess 补差 → chargeGroup(..., allowDebt=true)，
        catch BusinessException 仅剩「组已删/钱包行缺失」类系统错误才 backstop；欠款类不再兜底组长
    LlmBillingService 聊天 HOLD/结算同参数化
  ```
- 关键：删除「addUsed 0 行→throw→整单回滚」旧路径；类注释不变量②改为 `Σ(CONSUME+SELF_CONSUME+BACKSTOP−REFUND各腿)==used+debt总`。
- 验证：IT 四档算例（见 A5）；缺陷1 复演算例（池 6366.6/剩 250/差 489.95 → 池扣 489.95、组长 0、debt_pool 239.95、used 4239.95）。
- 依赖：A2。

## A4. 退款按腿反冲 + used 回减先还欠款

- 文件：`ProjectGroupWalletService.java`（refundGroup 重构）、`ProjectGroupMemberMapper.java`（subtractUsedDebtAware）
- 伪代码：
  ```
  doRefundGroup(gid, member, points, refType, refId):
    腿 = 按 refId 查组账本该任务各腿(type IN CONSUME/SELF_CONSUME/BACKSTOP，排除 REFUND)
    按腿比例反冲 points：poolLeg→组池 credit；selfLeg→creditSelf；backstopLeg→组长个人 credit
    （无腿记录的老任务回落现行单腿退组池）
    成员记账：d=min(points, debt总)：先 debt_leader 后 debt_pool 扣减（对应退回垫付方金额按腿比例），
              used 减 (points-d)，各自落 0 下限
    REFUND 腿分录同腿型；publish 一次聚合
  ```
- 验证：IT——退款额<欠款/恰等/>欠款 三档 + 混合腿任务全额退后 used+debt 归零。
- 依赖：A3。

## A5. 冻结闸与预检口径

- 文件：`media/service/MediaGenTaskService.java`（personalScope）、chat 侧预检（如有同口径点）、`billing/service/LlmBillingService.java`
- 伪代码：
  ```
  personalScope: inProjectAvailable = debt总>0 ? 0 : max(0, quota-used)
                 新增 debtTotal/debtPool/debtLeader/selfPoints 字段
  bindingConstraint=MEMBER 时话术：「欠款 X 未抵扣，暂停组内消费」或「项目内剩余不足」
  ```
- 验证：单测 scope 三态（欠款冻结/剩余不足/正常）；前端 media.ts 类型属 B 联动（B 校验）。
- 依赖：A3。

## A6. 测试与收口

- 文件：`ProjectGroupWalletServiceIT`（扩展）、既有单测适配（原「限额拒绝回滚」用例改口径）
- 验证：全量 `mvn test` 绿 + IT 绿；对账模板/下钻 SQL 加新不变量（查模板文件位置：billing 或 docs 对账模板，随 B 一起收口）。
- 依赖：A1-A5。
