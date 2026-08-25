---
description: "子计划 A：成员分配流水 + 降职缩额 + PENDING 邀请收口（规格 §2、§8.3.2）"
created-date: 2026-08-25
---

# 子计划 A：项目组额度与成员流水

> 主索引：[人工测试遗留问题修复.plan.md](人工测试遗留问题修复.plan.md)
> 规格：§2（降职缩额，Q1 拍板）、§8.3.2（成员分配流水）

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| `requireMember`（ProjectGroupService.java:489-495）被多处复用，直接改 FOR UPDATE 会扩大锁面 | 新增 `requireMemberForUpdate`（内部 `selectByGroupUserForUpdate`），仅降职路径用；其余调用点不动 |
| 锁序死锁：doChargeGroup 锁序 = 组池→成员行；降职若先锁子行再锁父行会反向 | 降职只锁目标成员行 A + UPDATE 子行（reparent/缩额计算读 A 行锁下快照），不锁组池——与 doChargeGroup 无环 |
| Σ直接下级 quota 含 NULL（不限额成员）算不出差 | 规格 §2.3：任一下级 NULL → quotaNew=used（保守） |
| 缩额把 quota 压低于 used | 公式自带 max(used, …)；单测覆盖边界 |
| MEMBER_* 新类型混入对账等式 | 流水写入带 type 常量；对账白名单在子计划 D 显式排除（此处只保证类型值不与资金类型撞名） |

## 实现步骤

> **实现期修正（2026-08-25）**：降职缩额流水类型从 `MEMBER_QUOTA_ADJUST` 改记 `MEMBER_RECLAIM`——缩额本质=收回授权，这样「净额=ΣALLOCATE−ΣRECLAIM」才正确反映有效授权；`QUOTA_ADJUST` 仅用于限额↔不限互转边界（delta=0）。规格 §2.3/§8.3.2 已同步。另：A1 落点实现为 `ProjectGroupService.recordMemberQuotaLedger`（计划原写放 WalletService，实际四落点全在 Service 层，就近内聚）。

- [x] **A1：组账本成员流水类型 + 四落点统一记流水**（2026-08-25 完成：实体常量+selectChildren+recordMemberQuotaLedger+updateQuota 三分支/insertMemberRow 两径挂钩；单测覆盖类型映射）
  - **目标**：成员配额每次变动都有历史（20x-2「累计被分配」数据源；降职缩额留痕）
  - **动作**：
    - `ProjectGroupLedgerEntity` 增类型常量：`MEMBER_ALLOCATE`（配额调增/落行）、`MEMBER_RECLAIM`（调减/回收）、`MEMBER_QUOTA_ADJUST`（非划拨性调整，如降职缩额）
    - `ProjectGroupWalletService` 增私有方法 `recordMemberQuotaChange(groupId, memberUserId, oldQuota, newQuota, cause)`：
      ```
      delta = (newQuota==null或oldQuota==null) ? 特殊标记0 : newQuota − oldQuota
      type  = delta>0 ? MEMBER_ALLOCATE : delta<0 ? (cause==DEMOTION ? MEMBER_QUOTA_ADJUST : MEMBER_RECLAIM) : return
      appendLedgerRow(当前池余额, groupId, memberUserId, type, delta, "MEMBER", memberRowId, cause文案)
      ```
    - 接入四处（同事务调用）：`ProjectGroupService.updateQuota`、`ProjectGroupInviteService` 接受落行、管理配额路径（MemberBudgetService 相关写 quota 处，Phase 3 定位）、A2 的降职缩额
  - **文件**：`projectgroup/entity/ProjectGroupLedgerEntity.java`、`projectgroup/service/ProjectGroupWalletService.java`、`projectgroup/service/ProjectGroupService.java`、`projectgroup/service/ProjectGroupInviteService.java`（≤20 ✓）
  - **依赖**：无
  - **验证**：单测——updateQuota 调增 50 → ledger 出 MEMBER_ALLOCATE(+50)；调减 → MEMBER_RECLAIM(−50)；不变 → 无行

- [x] **A2：降职缩额（核心修复）**（2026-08-25 完成：requireMemberForUpdate 行锁+selectChildren 快照+缩额三算例+RECLAIM 留痕；单测含反向/不限额/无变化用例）
  - **目标**：堵 17x-1「200 变 300」超发；Q1 拍板 quota−Σ下级
  - **动作**：`ProjectGroupService.updateMemberRole`（:445-472）降职分支改造：
    ```
    m = requireMemberForUpdate(groupId, memberUserId)          // 新增 FOR UPDATE
    if 降职(MANAGER→MEMBER):
        children = memberMapper.selectChildren(groupId, memberUserId)   // 直接下级快照
        reparentChildren(...)                                   // 现有
        if m.quota != null:
            if children 任一 quota==null: quotaNew = m.used
            else:                quotaNew = max(m.used, m.quota − Σchildren.quota)
            if quotaNew != m.quota:
                m.quota = quotaNew
                recordMemberQuotaChange(..., cause="管理降职缩额")      // A1
        m.role = MEMBER; updateById(m)
    ```
  - **文件**：`projectgroup/service/ProjectGroupService.java`、`projectgroup/mapper/ProjectGroupMemberMapper.java`（若缺 selectChildren 则补）、复用 A1 文件
  - **依赖**：A1
  - **安全检查**：`@AuditLog` 已在 Controller 层（核实，缺则补）；FOR UPDATE 防并发
  - **验证**：单测三算例（200/100→100；used150/分100→150；下级NULL→used）；并发测试：降职事务与 doChargeGroup 串行化、降职与组长直调子限额串行化（锁序回归）

- [x] **A3：PENDING 邀请收口（次生洞）**（2026-08-25 完成：accept→resolveAllocatedBy——邀请人仍 MANAGER 归其预算，否则〔降职/移除/admin 代发〕改挂组长；新建 ProjectGroupInviteServiceTest 6 用例）
  - **目标**：降职后接受的邀请不得挂在已是 MEMBER 的行下
  - **动作**：`ProjectGroupInviteService` 接受流程（:147 一带，Phase 3 核实行号）：
    ```
    allocRow = memberMapper.selectByGroupUserForUpdate(groupId, invite.allocatedByUserId)
    if allocRow == null 或 allocRow.role != MANAGER:
        inviterId = group.ownerUserId          // 改挂组长
    后续落行/预算校验照旧（组长侧 allocatable 硬卡）
    ```
  - **文件**：`projectgroup/service/ProjectGroupInviteService.java`
  - **依赖**：无（可与 A1 并行）
  - **验证**：单测——降职 A 后 A 的 PENDING 邀请被接受 → 成员行 allocated_by=组长且过组长预算校验；超预算 → 拒

- [x] **A4：测试与实测**
  - **单测**（2026-08-25 完成）：降职三算例（200/100→100、used150→150、下级NULL→used）+ 不限额管理仍 null + 无变化不落流水 + 流水类型映射（ALLOCATE/RECLAIM/QUOTA_ADJUST/不变）+ 邀请归属 6 用例；projectgroup 包全绿（54 例含真 PG IT）
  - **人工测试（必过门槛）**：留待 T1 钱路径门槛统一执行（测试方案 §一）

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 降职缩额生效 | 成员表「可分配」列/组卡片/chip/页顶下拉（17x①四处显示） | 读 quota 派生值自动变小 | 显示为零但不报错；刷新后一致 |
| quota 变化 | estimate personalScope（子计划 C） | 预估可用同步变 | 不限额仍显示「不限」 |
| MEMBER_* 落库 | 分配视图「累计被分配」（子计划 D3） | 聚合值增长 | 调减只动净额不动毛额 |

## 验证收口

- [ ] A1-A4 全绿；mvn test 通过；无锁序告警日志
