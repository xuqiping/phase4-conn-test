# Chunk B · 划拨/还款/调限额豁免/重置/退组结算 + 组页前端

> 规格 §4.1-§4.5/§5。依赖 A（debt 字段与腿型已就位）。

## B1. 个人划拨端点（self-transfer）

- 文件：`projectgroup/controller/ProjectGroupController.java`（或 MemberController 现挂载处）、`ProjectGroupWalletService.java`、`PointsWalletService.java`（如需 REF 常量）
- 伪代码：
  ```
  POST /api/project-groups/{gid}/members/self-transfer  body {points}
  @RequirePermission(projectgroup:member) + @AuditLog(point:self-transfer)
  服务（事务，锁序 个人→组池→成员）:
    校验: 本人是该组 deleted=0 成员; points>0
    个人钱包 charge(本人, points, REF_SELF_TRANSFER, gid, 「划拨至组内名下」)   -- 余额不足自然 INSUFFICIENT
    x = points
    rl = min(x, debt_leader): credit(组长个人, rl, REF_SELF_REPAY)…debt_leader-=rl; x-=rl
    rp = min(x, debt_pool):   组池 credit(rp); debt_pool-=rp; x-=rp
    if x>0: creditSelf(本人, x)
    落腿: SELF_ALLOCATE(+points 总额, balance_after=组池现值) 备注「个人划拨（还组长垫 rl/还组池垫 rp/入名下 x）」
    publish MEMBER+PERSONAL 各一次
  ```
- 验证：IT 三档（<欠款/恰等/大于）+ 余额不足拒 + 非成员 403 + 重复提交幂等（幂等键 self-transfer-{uid}-{gid}-{seq 或客户端传 nonce}）。
- 依赖：A。

## B2. 调限额豁免 + 重置清零

- 文件：`ProjectGroupWalletService.java`（updateQuota / resetUsed 所在服务）、流水备注
- 伪代码：
  ```
  updateQuota 调高 +X:
    d=min(X, debt_leader): debt_leader-=d; used-=d; X-=d
    d2=min(X, debt_pool):  debt_pool-=d2;  used-=d2; X-=d2
    quota += X（可能为 0）
    MEMBER_ALLOCATE 腿备注「限额 A→B（含抵扣欠款 原欠 Y）」
  resetUsed: used=0, debt_pool=0, debt_leader=0（self_points 不动）；ADJUST 腿备注「重置已用并清欠款 Z」
  ```
- 验证：IT——调高额 <欠款/恰等/>欠款 三档可用数（X−covered）；重置后欠款 0、名下余额不变。
- 依赖：A。

## B3. 退组/移除结算

- 文件：退组/移除成员服务方法（projectgroup/service，removeMember 或同义）、`PointsWalletService`
- 伪代码：
  ```
  removeMember（锁序 个人→组池→成员）:
    rl=min(self,debt_leader): credit组长; debt_leader-=rl; self-=rl
    rp=min(self,debt_pool): 组池 credit; debt_pool-=rp; self-=rp
    if self>0: credit(本人个人钱包, self, REF_SELF_REFUND)「退组退回名下余额」; self=0
    if debt合计>0: DEBT_WRITEOFF 腿留痕「退组核销欠款（组长垫 a/组池垫 b）」; 清零
    成员行软删（现行）；复活逻辑（revive）补 self_points=0
  ```
- 验证：IT 三分支（无欠款全额退/余额够清欠/余额清不完核销）+ 流水四腿断言。
- 依赖：A、B1（REF 常量）。

## B4. VO/事件/对账模板

- 文件：`projectgroup/dto/*VO`（成员行加 selfPoints/debtPoolPoints/debtLeaderPoints）、E1 事件 payload、对账下钻模板（billing 或 workflow docs 中的 SQL 模板文件）
- 伪代码：VO 三字段透出；PointsChangedEvent MEMBER scope payload 增字段；对账模板不变量行更新 `Σ腿==used+debt总` + SELF 分录对平式（规格 §3.3）。
- 验证：单测 VO 断言；模板 SQL 在测试库手工跑通（对账下钻三期口径回归）。
- 依赖：A。

## B5. 组页前端

- 文件：`frontend/src/views/ProjectGroupsView.vue`、`frontend/src/api/projectGroup.ts`（或对应 api 文件）、`frontend/src/stores/projectGroup.ts`（如事件字段透传）、`frontend/src/api/media.ts`（personalScope 类型）
- 伪代码：
  ```
  成员表: 新列「名下余额」「欠款」（欠款>0 红字，n-popover 拆分「组长垫 X · 组池垫 Y」）
  成员视角: 「我的组内账户」卡（名下余额/欠款明细/划拨按钮→弹窗: 金额输入+还款顺序文案+结果预览）
  提交面板(ImageGen/VideoGen/Chat 选组池时): 欠款红字「欠款 X 未抵扣，暂停组内消费」+「去划拨」跳组页锚点
  personalScope 类型补 debtTotal/debtPool/debtLeader/selfPoints
  ```
- 验证：vue-tsc 0；vitest 组件测（成员表列渲染/划拨弹窗校验）；E5 lastEvent 联动刷新复验。
- 依赖：B4。

## B6. 测试收口

- IT 全链：划拨→消费欠款→还款→冻结解除→退组 结串行场景一测到底。
- `mvn test` 全量 + 前端套件；commit。
