# 人工测试遗留问题修复III · Feature Map

> 2026-08-26。chunk A-F 全部文件位置+调用链+大白话注解。迁移仅 V161（无新表）。

## A/B · 积分瀑布与欠款模型（12x/7x 缺陷修复）

| 文件 | 作用 |
|---|---|
| `backend/db/migration/V161__project_group_self_and_debt.sql` | 成员表加 self_points（名下余额）/debt_pool_points/debt_leader_points（欠款两拆）；存量 used>quota 按流水比例回填拆分 |
| `billing/service/GroupBillingService.java` | **chargeGroup 瀑布**：组池腿→名下腿→兜底腿逐腿条件 UPDATE，业务性不足转下一腿不回滚（缺陷1 根因：旧版先扣后卡限额、失败整单回滚吞钱） |
| `projectgroup/service/ProjectGroupService.java` | selfTransfer（先还组长垫→组池垫→余款名下）/adjustQuota 豁免/resetUsed 清欠/leaveSettle 退组结算（残欠核销）/searchCandidates（E3 三字段） |
| `frontend/src/views/ProjectGroupsView.vue` | 组详情页：成员行欠款红字+划拨弹窗+自助划拨+批量邀请（UserPicker）+产出 tab（F 入库列） |

**调用链**：生成任务结算 → usage 落账 → chargeGroup 按瀑布三腿各自落 project_group_ledger（CONSUME/SELF_CONSUME/BACKSTOP）→ 欠款挂成员行 → 冻结闸（欠款>0 拒提交）→ 划拨/豁免/重置/退组四路清欠。
**大白话**：班费（组池）不够刷自己预存的（名下），再不够老师垫（组长），垫的记清楚谁垫的还谁。
**坑**：锁序死锁——个人钱包→组池→成员行顺序不能反；退款反冲按 ref+type 查腿防双退。

## C/D · 画布（2x）

| 文件 | 作用 |
|---|---|
| `frontend/src/components/canvas/CanvasBoard.vue` | 七小件宿主：默认图模型/比例副本尺寸/保存状态收口 updateNodeData |
| `frontend/src/components/canvas/MediaLightbox.vue`（D1） | 产物大图/视频弹窗（只收任务已存 URL，防注入） |
| `frontend/src/components/canvas/UpstreamPanel.vue`（D2） | 上游节点递归面板：edges 一次遍历 O(V+E)，禁逐节点查父 |
| `frontend/src/components/canvas/MentionTextarea.vue`（D3） | @弹层光标锚定：原生光标矩形定位替代镜像 div；双击上游卡插 @引用到末尾 |
| `frontend/src/components/canvas/mentionLogic.ts` | @序列化/插入（既有，防注入不动） |

**大白话**：@选人弹层跟着光标走不挡字；上游面板像「家谱」往上游溯源；Lightbox=点小图看大图。

## E · 认证三件（12x#2/3/4）

| 文件 | 作用 |
|---|---|
| `auth/service/AuthService.java` | lockedException（DB 落锁显解锁时间，未落库固定话术）；unlockUser（三清+条件 UPDATE 防并发） |
| `auth/controller/UserController.java` | PUT /{id}/unlock、PUT /{id}/name（user:manage+审计） |
| `common/audit/AuditLogController.java` | enrichOperatorMeta：同页一次 selectBatchIds JOIN 现姓名/备注（显示层） |
| `auth/mapper/UserMapper.java` | searchActiveCandidates：id/username/name/remark 三字段模糊（LIKE 调用方转义） |
| `frontend/src/components/common/UserPicker.vue` | 统一选人：search prop 注入数据源（三处权限各异），debounce 300ms+seq 防过期、键盘导航、a11y listbox |
| `frontend/src/utils/displayName.ts` | `name?.trim() || username` 全站口径 |
| `frontend/src/views/admin/UserManageView.vue` | 解锁按钮（autoLocked 显）+姓名弹窗 |
| `frontend/src/views/admin/WalletAdminView.vue` | 充值选人 UserPicker+批量模式（每人独立 uuid 幂等键，失败汇总） |

**大白话**：锁号告诉用户「几点自动解锁」并给管理员「提前放人」按钮；姓名=花名（账号=身份证号）；备注=「A 班」标签，按标签圈人打钱。
**坑**：纯 Mockito 测 LambdaQueryWrapper 须 @BeforeAll 注册 TableInfo；MockMvc 中文断言显式 UTF-8；R 字段名是 message。

## F · 产出一键入库（17x#1）

| 文件 | 作用 |
|---|---|
| `asset/service/AssetMediaBridgeService.java` | findExistingBySource（genMeta JSONB taskId+imageIdx 同项目判重）；existsBySourceTaskIds（批量 taskId→首资产） |
| `asset/controller/AssetMediaBridgeController.java` | GET /exists-by-source（上限 50） |
| `frontend/src/utils/groupOutputImport.ts` | isImportable（与预览列同口径）/parseImportedSet 纯函数 |
| `frontend/src/views/ProjectGroupsView.vue` | 产出「入库」列：已入库 tag+复用 SaveImage/SaveVideo/SaveChatToAssetDialog 三弹窗 |

**大白话**：组里生成的图/视频/对话一键存进资产库；同一项目存过就标「已入库」不再重复建卡；换项目还能再存一份。
**坑**：genMeta 是 JSONB 文本键比较（`->>'taskId'`）；存量行无 imageIdx 键判重不命中=向前兼容放行；判重不建唯一索引（并发双击由前端置态兜底）。

## 测试锚点

`AuthServiceTest` 53 · `UserUnlockIT` 3（真 PG）· `AssetMediaBridgeServiceTest` 8 · 组计费 IT 34 · 前端 `UserPicker.test` 6/`displayName` 4/`groupOutputImport` 6。全量：后端 2553 / 前端 822。
