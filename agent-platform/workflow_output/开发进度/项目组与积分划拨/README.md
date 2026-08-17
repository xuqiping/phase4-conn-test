# 项目组与积分划拨 · 功能 README

> 计划5（7x 积分系统 #3/#4/#5 相关）。Plan：`docs/plans/项目组与积分划拨.plan.md`；Step1-8 全部落地（进度详见 [开发进度1.md](开发进度1.md) / [开发进度2.md](开发进度2.md)）。

## 用户地图（B 类）

**谁用**：需要协同消耗积分的小团队——组长（出积分的人）+ 成员（干活的人）。

**场景**：组长把 500 分划进组池，组员在对话/图片/视频/知识库/画布五个入口选「项目组」干活，消耗全走组池不碰个人钱包；组长按人设限额（如每人 ≤100 分）防失控。

**效益**：
- 组员不用各自充值、报销；组长一处看全组流水与产出，不逐人对账。
- 限额按成员粒度，超支当场拦截；失败任务自动退款回组池。
- 账单页（admin 调用明细/我的消耗）有「项目组」列，组消耗与个人消耗一眼分清。

**入口**：
- 侧栏「项目组」（持 project-group:manage 权限码者可见）→ 我的组卡片 → 组详情（成员/流水/产出）。
- 五业务入口顶栏「参与项目」下拉（个人默认/我的组+余额徽标）。

## 技术说明（A 类）

### 数据模型（V133/V134 迁移）

- `project_groups`（组）/`project_group_members`（成员+quota_limit_points+used_points）/`project_group_wallets`（组池余额）/`project_group_ledger`（append-only 流水，balance_after 每行）。
- `llm_usage_logs.project_group_id`、`media_gen_tasks.project_group_id/estimated_cost` 加列（nullable，旧数据回归安全）。

### 记账链路

- **划拨/回收**：个人钱包↔组池，条件 UPDATE 防负；回收按「余额−在途占用」封顶；幂等键复用 @RateLimit。
- **同步消耗（chat/embed/rerank）**：计费时 gid 非空走 `chargeGroup`——组池条件扣+used 累加+CONSUME 行；失败退款 REFUND 行回滚两处。
- **异步媒体（图/视频）**：提交期三重预检（成员/组池/限额，估价快照入 estimated_cost）→ 结算 `chargeMedia` 幂等键 `media-charge-{taskId}` → 残余竞态组池尽 → **BACKSTOP**：差额扣组长个人+BACKSTOP 流水行（媒体成本已发生，不取 FAILED 让平台亏钱）。
- **锁序**：永远先个人后组（防死锁）；并发扣减全部条件 UPDATE。

### 可见性（拍板边界）

- 组长/admin：overview+全员产出+按成员筛；成员：仅自己产出行（不做成员报表）。
- 媒体文件预览不透出——下载端点归属门控（mayAccessFile）保持，产出仅元数据（taskId/状态/prompt 摘要）。

### 关键文件

| 层 | 文件 | 作用 |
|---|---|---|
| 后端-管理 | `projectgroup/ProjectGroupController.java` | 14 端点（建组/成员/限额/划拨/overview/outputs） |
| 后端-账务 | `projectgroup/ProjectGroupWalletService.java` | chargeGroup/refundGroup/allocate/reclaim/backstop |
| 后端-计费挂点 | `billing/LlmBillingService.java`、`billing/MediaBillingService.java` | 同步/异步两计费路径接组账 |
| 后端-查询 | `projectgroup/ProjectGroupQueryService.java` | overview/outputs（批查补名，无大宽 JOIN） |
| 前端 | `views/ProjectGroupsView.vue`、`components/projectgroup/ProjectGroupSelector.vue` | 推进页+五入口选择器 |
| 账单列 | `billing/BillingQueryService.java`+`LlmUsageLogMapper.xml` | 项目组列+筛选（LEFT JOIN 不滤软删） |

### 验证

- 后端单测 2166 绿（含并发/幂等/BACKSTOP/可见性 6+3 新用例）；前端 build 绿+vitest 588 绿。
- 人工测试方案：`docs/测试方案/项目组与积分划拨测试方案.md`（L1-L7 联动含反向/半选/批量）。

### 回滚注意

新表独立 drop 即回滚；两加列 nullable 可回滚。**已产生的组账流水回滚前必须导出**（对账依据）。
