# IV-D · 项目组默认额度与成员可见（17x-3 / 17x-4）

> 规格 §3.3、§3.4、决策 1/2/5/7。本轮风险最高 chunk（权限模型+额度语义），后端先行、前端跟随、三角色矩阵收口。

## 步骤

### D1 逐接口权限核对（17x-4 前置，必做第一步）
- **目标**：摸清 ProjectGroupController 全端点权限级别，确认只放宽 detail/overview。
- **动作**：列出全部端点 × requireRole/requireOwner/@RequirePermission 级别清单（表格落本 plan 附录/开发进度），标记：`GET /{id}`、`GET /{id}/overview` 两口放宽；其余（成员 CRUD/流水/审批/设置/邀请/移除…）**不动**。同时核对 overview 聚合内容——若含流水/欠款等管理数据，列入裁剪范围。
- **文件**：只读（不改码）
- **验证**：清单入开发进度文档；实现范围与清单一致。

### D2 默认 0 + 不限冻结（17x-3 后端）
- **目标**：全部入组路径落数值额度；成员行不可再写 NULL。
- **动作**（伪代码）：
  ```
  ProjectGroupInviteService.accept（:146-156）:
    quota = inv.quotaLimitPoints ?? BigDecimal.ZERO → insertMemberRow
  ProjectGroupInviteService.revive/invite（:102/:212 与 :60-117）:
    传 null → 按邀请默认 0 语义（DTO 不动，service 归一）
  ProjectGroupPoolService.approve（:228-231）:
    insertMemberRow(..., BigDecimal.ZERO, ...)；V156 注释同步改（0 有界，原毒化顾虑消失）
    审批通过返回话术/前端提示「该成员当前额度 0，请在成员表配额后可用」
  ProjectGroupService.updateQuota（Controller :275-280）:
    req.quotaLimitPoints == null → throw BusinessException(不限额度已停用，存量不限成员不受影响)
  建组组长自动行（Controller :47 注释口径）: 不改，仍 NULL（决策 7 豁免）
  ```
- **文件**：ProjectGroupInviteService.java、ProjectGroupPoolService.java、ProjectGroupService.java（3 个）
- **依赖**：D1
- **验证**：`mvn test -Dtest=MemberBudgetServiceTest,ProjectGroup*`；新增单测：默认 0 消耗被拦/配额后放行/存量 null 不回归/调额 null 抛 400/建组组长行仍 null。

### D3 普通成员受限视图（17x-4 后端）
- **目标**：组内 MEMBER 可读 detail/overview；额度字段按 viewer 裁剪。
- **动作**（伪代码）：
  ```
  ProjectGroupService.getDetail（:457-503）:
    权限: requireRole(MANAGER) → 「组内在册成员（MEMBER+）或 admin」（403 话术不变）
    组装 members（:470-493）单点裁剪:
      viewerRole == MEMBER && 行.userId != viewerId →
        quotaLimitPoints/usedPoints/selfPoints/debtPoolPoints/debtLeaderPoints/
        allocatablePoints/allocatedByUserId = null
      username/displayName/remark/role/joinedAt/owner 保留（决策 5）
      本人行、组长/MANAGER/admin 视角: 不裁剪
  ```
- **文件**：ProjectGroupService.java（1 个，裁剪单点）
- **依赖**：D1
- **验证**：单测三角色矩阵（组长全显/MEMBER 他人行 7 字段 null+本人行全显/非成员 403/admin 全显）；overview 聚合若含管理数据同口径。

### D4 前端：邀请弹窗+调额+受限成员 tab
- **目标**：三处 UI 跟随后端口径。
- **动作**（伪代码）：
  ```
  ProjectGroupsView 邀请弹窗（:417-440）: NInputNumber 默认 0、必填 ≥0、文案「进组后由组长/管理配额」
  调额 prompt（:1159-1160）: 必填数值，去「空=不限」；存量 null 行显示「不限（遗留）」
  成员 tab: v-if canManage → 拆两层——tab 本身对组内成员可见（isMemberOrAbove），
    管理列（限额/已用/名下/欠款/可分配/操作）仅 canManage 渲染；
    MEMBER 视图=受限列集（用户/角色/加入时间/备注）+ 本人行额度内联
  loadOverview（:860-862）: 非 canManage 不再 return，正常拉取受限数据
  其余 tab（流水/审批/设置）与默认 tab 逻辑不动
  ```
- **文件**：ProjectGroupsView.vue（1 个）
- **依赖**：D2/D3 后端就绪；A 轮已 commit（同文件邀请区）
- **验证**：vue-tsc 0；手动三账号（组长/MEMBER/admin）：MEMBER 见受限成员 tab+本人行、流水等仍 403；组长/admin 界面不回归（L14）。

### D5 全链路回归（curl 实测）
- **目标**：L13/L14 全档实测。
- **动作**：curl 脚本按 spec §8.2：邀请不填→接受→0→拦→配额→放行；池申请→批→0+提醒；调额 null→400；建组→组长行 null；三角色打 overview/detail 核对裁剪。
- **验证**：结果记入开发进度文档。

## 联动边界（对照 master L13-L14）

L13：预估/提交侧 quota−used=0 直接拒（修复III 既有逻辑自动生效，只需回归）；countChildUnbounded 不再计新成员；降职缩额不回归。L14：admin 代管 canManage 全显；产出 tab 默认页不变。

## 坑点

- BigDecimal 全程（分转点口径既有），落 0 用 `BigDecimal.ZERO` 勿 `new BigDecimal(0)`（检查器/等值比较）。
- D3 的 viewerRole 判定要复用 requireRole 同源角色查询，**勿另查一遍**（竞态+性能）；admin 判定走既有 isAdmin 参数口径。
- 前端「已裁剪=null」与「真无限=存量 null」显示冲突：MEMBER 视角他人行 null=不可见（显示 '-'），管理视角 null=不限（遗留）——两套渲染按 canManage 分流，勿共用一个格式化函数。

## 完成标准

`mvn test` 全量不红 + 三角色矩阵单测绿 + curl L13/L14 过 → commit `feat(project-group): 修复IV D 进组默认0+不限冻结+普通成员受限视图（17x-3/4）`。
