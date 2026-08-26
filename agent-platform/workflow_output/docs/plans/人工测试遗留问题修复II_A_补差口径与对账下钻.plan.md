---
description: "子计划 A：BACKSTOP 差额计入成员 used（钱 bug §1）+ 对账/分配按组下钻（§2）"
created-date: 2026-08-26
---

# 子计划 A：补差口径与对账下钻

> 主索引：[人工测试遗留问题修复II.plan.md](人工测试遗留问题修复II.plan.md)
> 规格：§1（7x-2 钱 bug，Q1=A）、§2（7x-1 对账下钻）。Q1 追问：组长不够扣 → 扣到 0 + DEBT 欠款（现网已实现，不属本计划改动）。

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| backstop 用条件版 addUsed 会被 quota 卡住（quota 紧时 used+550>quota 返 0 行，静默失败） | 新 mapper 方法 `addUsedUnconditional`（无 WHERE quota 条件，仅 WHERE id），单测覆盖 quota 耗尽场景 |
| backstop 签名改动漏调用方 | 全库 grep `backstop(` 调用点清单化（已知 2 个：MediaBillingService.backstopMedia:384-392、全量结算 :118-127；实现期再 grep 确认无第三方） |
| 全量结算路径（无 HOLD 存量任务）BACKSTOP 不走新逻辑 | 修复放 `ProjectGroupWalletService.backstop` funnel 内部，两路径自动覆盖 |
| 存量修复映射不到成员（任务行已物理清理/跨库） | 宁缺勿错：跳过+计数+迁移日志打印 ref_id 清单；幂等设计（重复执行不双加——按 ledger 行 id 去重标记） |
| 存量补 used 后对账 crossDiff 变红 | crossDiff 是组账本 vs points_ledger 镜像腿比对，不含 used 列——不受影响；但**人工必须核**一遍对账 tab 仍平 |
| 对账端点 includeAll=true 全组行 + totals 跟随筛选，两语义叠加搞混 | 参数语义表先写死：groupId 选中→单组行+totals=该组；includeAll 且无 groupId→全组行+totals=全平台；都不传→Q9=A 现状 |
| 前端 groups 与 abnormalGroups 双数据源切换闪烁 | 单一 computed：`rows = resp.groups ?? resp.abnormalGroups ?? []`；abnormalGroups 字段保留兼容 |

## 实现步骤

- [x] **A1：backstop 计入成员 used（后端核心）**（commit 8e45a9e；LlmBillingService.backstopChat 为计划漏记的第三方调用方，一并透传；锁序定为 个人→组池→成员）
  - **目标**：BACKSTOP 兜底后成员 used = 真实消耗，组侧与账单汇合
  - **动作**：
    ```
    ProjectGroupMemberMapper 增：addUsedUnconditional(memberId, delta)
        UPDATE ... SET used_points = used_points + #{delta} WHERE id = #{id}
    ProjectGroupWalletService.backstop 增参 consumerUserId：
        兜底扣组长个人成功后（charge 或 chargeToDebt 之后，同事务）：
        memberMapper.addUsedUnconditional(消费成员行.id, shortfall)
    两个调用方透传提交人 uid：
        MediaBillingService.backstopMedia（:384-392）传 userId
        MediaBillingService 全量结算 BACKSTOP 分支（:118-127）传 userId
    类注释「对账不变量②」改写：
        BACKSTOP 计入 member.used（used=真实消耗，不论资金来源）；
        组池 balance 不含 BACKSTOP（资金出自组长个人）
    ```
  - **文件**：`projectgroup/mapper/ProjectGroupMemberMapper.java`、`projectgroup/service/ProjectGroupWalletService.java`、`billing/service/MediaBillingService.java`
  - **依赖**：无
  - **验证**：单测四例——①组池富余补差三侧数值；②组池不足→组长扣款+used 含差额+组池不变；③addUsedUnconditional quota 耗尽仍成功；④退差腿回归（refundGroup 不受影响）

- [x] **A2：次生修复（FAILED usage 带 gid + BACKSTOP remark）**（commit aadcf5e）
  - **目标**：失败行可按组过滤；兜底行语义自解释
  - **动作**：
    - `MediaBillingService.java:145-146、299-300` 两处 `usageCollector.record` 补传 projectGroupId（从 task/请求上下文取）
    - backstop 落组账本行 remark 补「补差兜底，差额由组长个人承担」（新行起；`ProjectGroupWalletService` backstop 落账处）
  - **文件**：`billing/service/MediaBillingService.java`、`projectgroup/service/ProjectGroupWalletService.java`
  - **依赖**：无
  - **验证**：构造组任务计费失败 → usage 行 project_group_id 非空；BACKSTOP 行 remark 含兜底文案

- [x] **A3：迁移——存量 BACKSTOP 补 used（一次性数据段）**（commit ae8000c0；V158 已被占用 → V159；纯 SQL DO block 而非 Java Migration，原因见迁移头注释；本地实测回填/幂等/排除新行全过）
  - **目标**：历史兜底差额回填进成员 used，存量口径也汇合
  - **动作**（伪 SQL，Flyway Java migration 或纯 SQL+存储过程按实现期定，倾向 Java Migration 便于映射）：
    ```
    for row in project_group_ledger WHERE type='BACKSTOP' AND deleted=0:
        task = media_task by ref_id 解析（ref 含 taskId）
        consumer = task.user_id；member 行 = (group_id, consumer)
        幂等标记：ledger 行 detail/remark 无 'used-synced' 标记才执行
        UPDATE member SET used_points = used_points + |row.delta|
        打标记（remark 追加 'used-synced'）
        映射不到 → skipCount++，日志打印 ref_id
    迁移日志输出：处理 N 笔 / 跳过 M 笔（附清单）/ 总补加积分
    ```
  - **文件**：`db/migration/V158__backstop_used_sync.java`（新，Java Migration）
  - **依赖**：A1（语义先行）
  - **验证**：本地库跑迁移前后 used 对比抽样；重复执行第二遍零变更（幂等）；对账 tab 仍全平

- [x] **A4：对账端点扩展（后端）**（commit 4b252481；单测 +4：单组命中/含平组全列表/未命中/gid 优先）
  - **目标**：支持按组下钻与全组视图
  - **动作**：
    ```
    GET /billing/admin/group-reconcile 增可选参：
        groupId(Long)、includeAll(Boolean default false)
    GroupReconcileMapper.selectGroupRawRows 增动态 WHERE g.id = #{groupId}
    BillingReconcileService：
        rows = rawRows(groupId)
        选中 groupId → groups=[该组行(带 balanced)]，totals=该组聚合
        includeAll && 无 groupId → groups=全组行，totals=全平台
        都不传 → 现状（仅异常组 + 全平台 totals）
    GroupReconcileVO 增 groups[]（行结构复用 GroupReconcileRowVO + balanced）
    ```
  - **文件**：`billing/controller/BillingController.java`、`billing/service/BillingReconcileService.java`、`billing/mapper/GroupReconcileMapper.java`、`billing/dto/GroupReconcileVO.java`
  - **依赖**：无
  - **验证**：单测——groupId 命中/未命中；includeAll 行数=组数；totals 跟随筛选数值断言；零参回归 Q9=A

- [x] **A5：对账前端（下拉+开关+状态列）**（commit e27180b0；vue-tsc 全绿）
  - **目标**：对账 tab 可选组看单组账
  - **动作**：
    - `BillingAdminView.vue` 对账 tab：复用 `groupOptions` 加组下拉（与分配 tab 同款，clearable）+「显示全部组」n-switch（includeAll）；变化重查
    - 表数据源 computed：`groups ?? abnormalGroups`；行增「状态」列（balanced ? 「平」tag : 异常红 tag）
    - api `adminGroupReconcile(params)` 增可选参（`frontend/src/api/billing.ts:534-535`）
  - **文件**：`frontend/src/views/BillingAdminView.vue`、`frontend/src/api/billing.ts`
  - **依赖**：A4
  - **验证**：人工——选账平组见完整行+「平」+顶卡=该组；清筛选回默认；开全部组见全组行异常标红

- [ ] **A6：测试收口**
  - 规格 §1.6、§2.6 人工测试点全过；回归：V155 HOLD 多退少补语义（组池够/不够/退差三态）

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 补差组腿失败 | 组长个人钱包/欠款 | 差额扣组长（或挂 DEBT） | 组长侧结局不影响成员 used 计入 |
| 同上 | 成员 used | +差额=实耗 | quota 耗尽不卡（无条件加） |
| 对账 tab 组筛选 | 顶卡合计 | =该组数值 | 清筛选回全平台；开关与下拉互斥语义见 A4 |
| includeAll 开关 | 表格 | 全组行含正常组 | 有 groupId 时开关无效（单组优先） |
| A3 存量回填 | 对账 crossDiff | 不变（不含 used） | 人工核对一遍仍平 |

## 验证收口

- [ ] A1-A6 全绿；问题原话场景复演：组流水两条 + 已使用=实耗 + 账单=实耗 + 组长侧含 550，三者闭环
