# 人工测试遗留问题修复IV · README

> 2026-08-27 全 chunk 完成（A→B→C→D→E→F）。规格 [IV 设计](../../docs/specs/人工测试遗留问题修复IV设计.md) · master [plan](../../docs/plans/人工测试遗留问题修复IV.plan.md) · 进度 1-6。

## 用户地图（谁用/场景/效益）

| 用户 | 场景 | 效益 |
|---|---|---|
| 项目组长 | 新人进组默认零额度、按需调限额；邀请批量选人 | 进组不再默认「随便刷」，额度收紧一步到位；搜「A 班」→全选一把发邀请 |
| 组管理（MANAGER） | 代组长拉候选发邀请、看组财务 | 候选权限对齐不再 403 卡壳 |
| 组成员（学员） | 看本组组织（谁在/角色/备注）、自己的额度 | 组织信息可见、他人钱包不可见；自己额度透明 |
| 财务（usage:view） | 按组织备注对账（A 班全班充值/消耗/余额） | 「按备注汇总」一屏看桶，免逐人加；四视图备注列认人 |
| 画布用户 | 两段式点节点、四角四边拖尺寸、面板拖宽、失焦即存、副本独立操作 | 不再一碰就弹窗；手柄点得中；副本重生成/入库不伤原件 |
| 平台管理员 | 审计列表直接认人 | 列表即显「姓名（账号）+备注」，不用点详情 |

## 技术说明（一屏版）

- **A 前端小件**（`58f64beb`）：审计列显姓名备注（AuditLogView，账号快照留详情）；UserPicker 全选/反选（结果快照作用域+请求中禁用）与下拉时机（聚焦/输入才拉候选）；候选接口 requireOwner→requireRole(MANAGER)（只放宽读）；分辨率选择器整行。
- **B 画布交互**（`b88f61fb`）：两段式点击 composable useMediaPreviewClick（选中态才开 Lightbox）；上游面板全直显按类型分色；resize 角热区 12px+四边 Line 控件（热区收内侧不挡连线）；面板拖宽 localStorage `canvas.propPanel.width`（NaN/越界回落 260）。
- **C 保存与副本**（`d843b8a6`）：structure-changed 补发——新增节点即入防抖保存链；文本失焦保存（blur 延迟后未点候选才触发）；新建 image/video 节点即定型尺寸；副本完全独立（nodeClone 产物 fileId 拷贝/新引用，与原件零共享——用户决策）。
- **D 项目组**（`75920ed0`）：进组默认 0 三口堵（新邀请/池审批/存量 PENDING 接受兜底）；updateQuota 拒 null→400「不限额度已停用」（新写入不再产生 NULL 态，存量 NULL 冻结显「不限（遗留）」，建组组长行豁免）；MEMBER 受限视图（getDetail 单点裁剪：他人行额度字段隐藏、组财务不透出、流水仅本人；detail/overview 两读口放行，其余端点不动）。
- **E 备注汇总**（`98d0eaf2`）：GET /billing/admin/remark-summary（usage:view）：users LEFT JOIN 余额/PAID 充值/**窗内**消耗，GROUP BY COALESCE(remark,'')（NULL/'' 同桶），LIMIT 1000；余额/充值=全量累计、消耗/调用=查询窗（默认 30 天/上限 365）；四视图 +remark 列；9 处 keyword 块扩备注 LIKE 转义。
- **迁移**：**零迁移零数据订正**（决策 1 不迁移存量 NULL），全部改动代码级可回滚。

## 关键文件速查

| 域 | 文件 |
|---|---|
| 默认 0/受限视图 | `projectgroup/service/ProjectGroupService`（updateQuota/candidates）、`ProjectGroupInviteService`、`ProjectGroupPoolService`、`ProjectGroupQueryService`（裁剪单点）、`views/ProjectGroupsView.vue` |
| 画布 | `components/canvas/`：CanvasBoard.vue（手柄/保存链）、PropertyPanel.vue（上游/拖宽/整行）、useMediaPreviewClick.ts（两段式）、nodeClone.ts（副本独立）、MentionTextarea.vue（失焦存） |
| 备注汇总 | `billing/controller/BillingController`、`billing/service/BillingQueryService`、`billing/mapper/`（UserPointsBalanceMapper/PaymentOrderMapper/GroupAllocationMapper/LlmUsageLogMapper.xml）、`views/BillingAdminView.vue` |
| 审计认人 | `views/admin/logs/AuditLogView.vue` |
| 选人组件 | `components/common/UserPicker.vue` |

## 对应功能文档（按功能查，2026-08-27 已同步增补）

| Chunk | feature-map | 用户操作手册 |
|---|---|---|
| A/D 项目组 | [项目组与积分划拨](../../docs/feature-map/项目组与积分划拨.feature-map.md) | [项目组与积分划拨](../../docs/user-ops/项目组与积分划拨用户操作手册.md) |
| B/C 画布 | [无限画布创作页](../../docs/feature-map/无限画布创作页.feature-map.md) | [无限画布创作页](../../docs/user-ops/无限画布创作页用户操作手册.md) |
| C 副本独立 | [项目资产库](../../docs/feature-map/项目资产库.feature-map.md) | [项目资产库](../../docs/user-ops/项目资产库用户操作手册.md) |
| E 备注汇总 | [积分计费系统](../../docs/feature-map/积分计费系统.feature-map.md) | [积分计费系统](../../docs/user-ops/积分计费系统用户操作手册.md) |
| A 审计认人 | [日志系统](../../docs/feature-map/日志系统.feature-map.md) | [日志系统](../../docs/user-ops/日志系统用户操作手册.md) |
| A 选人组件 | [认证系统增强](../../docs/feature-map/认证系统增强.feature-map.md) | [认证系统增强](../../docs/user-ops/认证系统增强用户操作手册.md) |

> 本批次不设独立 user-ops/feature-map（同 III 惯例）；内容已并入上表六套功能文档的「2026-08-27 增补（修复IV）」节。

## 测试与验证

后端 mvn test **2566 全绿**（IT 14/14 真 PG）；前端 vitest **846 全绿**+vue-tsc 0；备注汇总实库对账四口径全平（桶 Σ=原表直查）。人工测试方案（L1-L14 联动+T1-T5）见 [测试方案](../../docs/测试方案/人工测试遗留问题修复IV测试方案.md)。三份问题文档（17x/12x/2x）未解决项已全部勾销清零。
