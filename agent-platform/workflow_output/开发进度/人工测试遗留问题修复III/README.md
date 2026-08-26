# 人工测试遗留问题修复III · README

> 2026-08-26 全 chunk 完成（A→B→D→C→E→F）。规格 [III 设计](../../docs/specs/人工测试遗留问题修复III设计.md) · master [plan](../../docs/plans/人工测试遗留问题修复III.plan.md) · 进度 1-6。

## 用户地图（谁用/场景/效益）

| 用户 | 场景 | 效益 |
|---|---|---|
| 项目组长 | 成员超限额消耗的补差、欠款跟踪、批量邀请/充值全班、产出归档 | 钱不黑洞：欠款按来源拆分可追讨、可豁免；产出一键入库不散失 |
| 组成员（学员） | 划款入组、欠款冻结提示、还款 | 欠多少/欠谁一目了然，还清即刻解冻 |
| 财务（points:recharge） | 按备注「A 班」筛人批量同额充值 | 免逐人点选；失败名单可单独重充不双扣 |
| 平台管理员 | 被锁账号提前解锁、给用户设姓名/备注、审计认人 | 锁号不再干等 15min；改名后审计仍能认人 |
| 画布用户 | 上游追溯、Lightbox、@引用光标锚定、自动保存状态 | 引用插到光标处、双击上游即引用、保存状态可见 |

## 技术说明（一屏版）

- **A/B 积分瀑布与欠款**（V161 迁移）：消耗按 组池→名下余额→组长兜底 三腿结算，溢出按垫款来源拆 debt_pool/debt_leader；划拨先还组长垫再还组池垫；调高限额=豁免抵扣；退组结算残欠核销。锁序沿用 个人→组池→成员（防死锁）；各腿独立条件 UPDATE，业务性不足不整单回滚（缺陷1 根因修复）。
- **C/D 画布**：Lightbox（产物大图/播放）、上游节点递归面板（O(V+E) 遍历）、@弹层光标锚定（原生光标矩形定位）、自动保存状态徽标+离开确认、七小件（默认图模型/比例/副本/尺寸/点击/保存状态）。
- **E 认证三件**：已锁账号登录话术带解锁时间（未落库锁维持固定话术防 oracle）；PUT /users/{id}/unlock 三清（DB+Redis 计数+ban）仅自动锁可解；姓名端点+displayName util；审计详情 operatorName/operatorRemark 显示层 JOIN（username 列保写入快照）；UserPicker 统一选人（数据源注入 search prop，debounce+键盘+a11y），三处替换+批量充值模式（每人独立幂等键）。
- **F 产出一键入库**：from-media 同项目判重（genMeta JSONB taskId+imageIdx）；GET /assets/exists-by-source 批量回填「已入库」；产出列复用三个既有入库弹窗。
- **迁移**：仅 V161（project_group_members +self_points/debt_pool_points/debt_leader_points，回滚=DROP COLUMN 无损）；2x/12x/17x 免迁移。

## 关键文件速查

| 域 | 文件 |
|---|---|
| 瀑布结算 | `billing/service/GroupBillingService`（chargeGroup 瀑布）、`projectgroup/service/ProjectGroupService`（selfTransfer/退组） |
| 解锁/姓名 | `auth/service/AuthService`（lockedException/unlockUser）、`auth/controller/UserController` |
| 选人组件 | `frontend/src/components/common/UserPicker.vue`、`utils/displayName.ts` |
| 入库 | `asset/service/AssetMediaBridgeService`、`utils/groupOutputImport.ts` |
| 画布 | `frontend/src/components/canvas/*`（Lightbox/上游面板/MentionTextarea） |

## 对应功能文档（按功能查，2026-08-26 已同步增补）

| Chunk | feature-map | 用户操作手册 |
|---|---|---|
| A/B 欠款/划拨 | [项目组与积分划拨](../../docs/feature-map/项目组与积分划拨.feature-map.md) | [项目组与积分划拨](../../docs/user-ops/项目组与积分划拨用户操作手册.md) |
| C/D 画布九项 | [无限画布创作页](../../docs/feature-map/无限画布创作页.feature-map.md) | [无限画布创作页](../../docs/user-ops/无限画布创作页用户操作手册.md) |
| E 解锁/姓名 | [认证系统增强](../../docs/feature-map/认证系统增强.feature-map.md) | [认证系统增强](../../docs/user-ops/认证系统增强用户操作手册.md) |
| E 批量充值 | [积分计费系统](../../docs/feature-map/积分计费系统.feature-map.md) | [积分计费系统](../../docs/user-ops/积分计费系统用户操作手册.md) |
| E 审计认人 | [日志系统](../../docs/feature-map/日志系统.feature-map.md) | [日志系统](../../docs/user-ops/日志系统用户操作手册.md) |
| F 入库判重 | [项目资产库](../../docs/feature-map/项目资产库.feature-map.md) | [项目资产库](../../docs/user-ops/项目资产库用户操作手册.md) |

> 本批次不设独立 user-ops/feature-map（批次名看不出对应功能）；内容已并入上表六套功能文档的「2026-08-26 增补（修复III）」节。

## 测试与验证

后端 mvn test **2553 全绿**（含真 PG IT 34：瀑布/欠款/还款/退组/解锁）；前端 vitest **822 全绿**+vue-tsc 0。人工测试方案（L1-L10 联动+T1-T5）见 [测试方案](../../docs/测试方案/人工测试遗留问题修复III测试方案.md)。
