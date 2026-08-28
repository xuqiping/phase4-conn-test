# IV-E · 按备注汇总统计（12x-1，决策 4：独立视图 + 备注列）

> 规格 §4.1。后端一端点一 XML + 三 mapper 扩列扩 keyword；前端一新 tab + 四视图备注列。

## 步骤

### E1 remark-summary 端点
- **目标**：`GET /api/billing/admin/remark-summary` 按备注聚合。
- **动作**（伪代码）：
  ```
  BillingController: @RequirePermission(usage:view)（沿用同组端点惯例）
    → BillingQueryService.remarkSummary(timeRange?)
  新 XML 查询（放 LlmUsageLogMapper.xml 或独立）:
    users u LEFT JOIN 余额 LEFT JOIN (payment_order PAID GROUP BY user) LEFT JOIN (llm_usage_logs GROUP BY user)
    GROUP BY COALESCE(u.remark,'') ORDER BY consume_sum DESC NULLS LAST LIMIT 1000
    逻辑删除条件照既有 mapper 惯例；remark 空桶=「未填备注」前端渲染
  VO: { remark, userCount, balanceSum, rechargePointsSum, rechargeAmountSum, consumePointsSum, callCount }
  ```
- **文件**：BillingController.java、BillingQueryService.java、对应 Mapper 接口 + XML、新 VO（约 5 个）
- **依赖**：无
- **验证**：`mvn test -Dtest=Billing*`；单测：聚合数与 by-user 手工和一致 / 未填备注桶 / LIMIT 生效 / 空表不炸。

### E2 四视图备注列 + keyword 扩 remark
- **目标**：by-user / user-balances / recharges / group-allocations 行级带 remark；keyword 命中备注。
- **动作**（伪代码）：
  ```
  LlmUsageLogMapper.xml groupByUser: SELECT 增 u.remark → UsageDimensionVO 加 remark
  UserPointsBalanceMapper: 行 VO 加 remark；keyword OR u.remark LIKE 转义(照 UserController:76-78)
  PaymentOrderMapper（:71-72/:91-92）、GroupAllocationMapper（:27-28/:63-64）: 同上
  ```
- **文件**：4 个 Mapper（接口/XML/VO 视实际归属，约 6-8 个）
- **依赖**：无
- **验证**：`mvn test -Dtest=Billing*`；curl：keyword=备注串命中该备注全部用户；转义 `%_` 通配符用例。

### E3 前端「按备注」tab + 四视图备注列
- **目标**：BillingAdminView 新 tab 与四视图列。
- **动作**（伪代码）：
  ```
  api/billing.ts: remarkSummary 类型 + 调用
  BillingAdminView: 新「按备注汇总」tab——表列: 备注(空显「未填备注」)/人数/消耗积分/调用次数/充值积分/充值金额/余额合计；行首色条区分
  四视图行内加备注列（n-tag 灰、悬浮全文，照 UserPicker 行样式）
  ```
- **文件**：api/billing.ts、BillingAdminView.vue（2 个）
- **依赖**：E1/E2
- **验证**：vue-tsc 0；手动：汇总数字与明细对账一致；备注列悬浮显示；未填备注桶显示。

## 联动边界

- keyword 扩 remark 后，原按 username/name 搜索结果集可能变宽（多命中备注同名用户）——预期行为，手册写明。
- 汇总视图与 by-user 明细口径：同一时段口径需一致（若有 timeRange 参数，两视图同参对账）。

## 坑点

- llm_usage_logs 子查询全表聚合——**必须**先 WHERE 时段（默认近 30 天可选）再 GROUP BY，禁无界全表；LIMIT 1000 兜底。
- `COALESCE(u.remark,'')` 空串与 NULL 同桶；前端判空用 `!remark` 勿只判 `=== ''`。
- BigDecimal 金额合计序列化精度沿用既有 VO 惯例（勿 double）。

## 完成标准

`mvn test -Dtest=Billing*` 绿 + 前端 vue-tsc 0 + 手动对账过 → commit `feat(billing): 修复IV E 按备注汇总统计+四视图备注列（12x-1）`。
