# 人工测试遗留问题修复IV设计（项目组可见性 / 备注统计 / 画布体验清零）

> 对应问题文档：`workflow_output/人工测试问题/17x_项目组.md`、`12x_认证系统.md`、`2x. 资产库和无限画布.md` 的「未解决」全部 14 项，另含探查中发现的 1 项顺带缺陷（候选接口权限不一致）。
> 前序：修复设计 / II / III（`人工测试遗留问题修复设计.md` 系列）。本文档沿用 III 的结构惯例：根因 → 已确认口径 → 分域设计 → 汇总（迁移/联动/测试/边界）。

## 1. 背景与缺陷清单

三份人工测试问题文档的未解决项，经三路代码探查全部定位根因：

| # | 问题 | 根因（file:line） | 批 |
|---|---|---|---|
| 17x-1 | 选人组件无全选/反选 | UserPicker.vue 自绘 listbox 无批量操作；候选前端截 20 条（:121） | A |
| 17x-2 | 邀请弹窗一打开下拉就弹出 | input focus 即 openList+空词搜索（UserPicker.vue:26、:130-135） | A |
| 17x-2b | （顺带缺陷）管理打开邀请弹窗选人 403 | 候选接口 requireOwner vs 邀请 requireRole(MANAGER) 口径不一（ProjectGroupService.java:510 vs ProjectGroupInviteService.java:60-62） | A |
| 17x-3 | 成员进组默认不限而非 0 | 邀请未填限额落 null=不限（ProjectGroupInviteService.java:146-156）；公共池入组恒 null（ProjectGroupPoolService.java:228-231） | D |
| 17x-4 | 普通成员看不到成员列表/自己额度 | getDetail 第一行 requireRole(MANAGER) 直接 403（ProjectGroupService.java:458）；前端成员 tab 包在 v-if canManage（ProjectGroupsView.vue:137-166） | D |
| 12x-1 | 无按备注汇总统计 | 四张 admin 统计视图只按用户维度，keyword 也不匹配 remark | E |
| 12x-2 | 审计列表无姓名/备注 | 后端已回填 operatorName/operatorRemark（AuditLogController.java:86-103），前端列表列只显 username（AuditLogView.vue:159-161）——纯前端缺口 | A |
| C-1 | 点节点立即弹大图 | onNodeClick 选中+emit preview-media 同帧（CanvasBoard.vue:681-687） | B |
| C-2 | 更上游折叠、类型不区分 | depth>1 默认折叠（PropertyPanel.vue:45-49、:934）；类型仅文字徽标（:938-941） | B |
| C-3 | resize 手柄难点中、缺四边 | 仅四角 Handle 用库默认 5px 样式无热区（**热区**=可点击命中的透明扩大区域）扩展；四边因怕压连线手柄被有意裁掉（CanvasNodeBase.vue:14-15） | B |
| C-4 | 新增节点/失焦/改参数不自动保存 | addNode 不 emit structure-changed（CanvasBoard.vue:591-609）；文本 v-model 直写不 emit data-changed（PropertyPanel 多处）；比例/时长/分辨率/audioMode 同样漏（:384/389/393/608）；MentionTextarea blur 只关候选层（MentionTextarea.vue:360-363） | C |
| C-5 | 分辨率选择器窄 | 时长+分辨率同挤一行各约 118px（PropertyPanel.vue:386-395）；比例独占整行先例在 :381-385 | A |
| C-6 | 右侧面板不可拖宽 | aside 固定 260px（PropertyPanel.vue:1326-1327），无 splitter（拖拽分隔条） | B |
| C-7 | 新建节点尺寸≠生成结果 | 新建默认 200×自适应（CanvasBoard.vue:550-555）；定型 320×320（updateNodeData:989-999） | C |
| C-8 | 副本只复制文本参数 | nodeClone.ts RESET_KEYS（:12-22）把产物与绑定全删 | C |

## 2. 已确认口径（用户 6 项决策，2026-08-27）

1. **17x-3 不迁移存量**：已入组的 null（不限）成员维持不变，不做数据订正。
2. **17x-3 取消新邀请的「不限」**：新进组一律数值额度（默认 0）；「不限」降级为**冻结遗留态**——仅存量 null 行保留，任何新写入路径（邀请/公共池/调额度）只接受数值；数值化后不可回 null。
3. **C-8 副本完全独立**：清资产绑定三件套（assetId/assetName/assetVersion）+ taskId；产物四件（fileId/previewUrl/coverPreviewUrl/outputText）保留。
4. **12x-1 独立汇总视图 + 备注列**：新增「按备注汇总」视图；现有四视图加备注列与备注 keyword。
5. **17x-4 组织信息可见**：他人行显用户名/姓名/角色/加入时间/备注；额度类字段（限额/已用/名下/欠款/可分配/归属上级）隐藏；本人行全显。
6. **C-1 严格两段式**：未选中时任何点击（含点图片）只选中；已选中后再点媒体本体才弹 Lightbox。
7. **17x-3 公共池入组同样落 0**（追问补充，2026-08-27）：决策 2 覆盖**全部入组路径**（邀请+公共池申请）；公共池自助入组从「组池直管不限」改为「批准后 0，组长再配额」。**唯一豁免：建组时组长自动成员行维持 NULL**——组长是预算归属人，其 quota NULL 是层级额度模型的根语义（ProjectGroupController.java:47），不在收紧范围。

## 3. 项目组（17x，4 项 + 1 顺带缺陷）

### 3.1 UserPicker 全选/反选（17x-1）

**改动**：`frontend/src/components/common/UserPicker.vue`
- listbox 底部加操作行：「全选当前候选」「反选」，作用于**当前已加载候选集**，merge 进 modelValue（全选=并集；反选=对当前候选逐个翻转：在选中的移出、未选中的加入）。
- 操作行右侧显示「已选 N / 候选 M」。
- 前端截断 20→50，与后端 searchCandidates 空词 50/非空 20 对齐；候选>50 时操作行明示「当前候选 M 条」，**不假装全量**。
- chips 区（:4-14）支持换行折叠，防大量选中溢出。

**影响**：组件单点，[ProjectGroupsView.vue:419](agent-platform/frontend/src/views/ProjectGroupsView.vue#L419)（邀请）与 [WalletAdminView.vue:14](agent-platform/frontend/src/views/admin/WalletAdminView.vue#L14)（批量充值）自动受益；无后端改动。同步改 UserPicker.test.ts。

### 3.2 邀请下拉时机 + 候选权限对齐（17x-2 / 17x-2b）

**下拉时机**：UserPicker 区分「程序获焦」与「用户交互」——focus 事件不再直接 openList；改为 input 的 mousedown/click 或首次输入时打开。弹窗打开瞬间（程序获焦）不弹候选。
**权限对齐**：`ProjectGroupService.searchCandidates`（:510）requireOwner → requireRole(MANAGER)，与发邀请权限（ProjectGroupInviteService.java:60-62）一致。只放宽读候选，不放大写权限。

**影响**：批量充值页同款体验改善（无回归）；管理角色邀请链路修复 403。

### 3.3 成员进组默认 0 + 「不限」冻结（17x-3，本轮后端改动半径最大）

**写入路径全数收紧**：
| 路径 | 现状 | 改为 |
|---|---|---|
| 接受邀请 insertMemberRow（ProjectGroupInviteService.java:146-156） | inv.quotaLimitPoints 未填→null | 未填→**0** |
| 公共池申请通过（ProjectGroupPoolService.java:228-231） | 恒 null（V156 故意：组池直管、预算挂组长） | **0**（决策 7；V156 注释随之更新——0 有界，原「不限额下级毒化审批管理可分配」的顾虑不复存在） |
| 邀请弹窗（ProjectGroupsView.vue:425-430） | 「空=不限」 | 默认 0，只能填数值；文案改「进组后由组长/管理配额」 |
| 组长/管理调成员限额（`PUT /{id}/members/{uid}/quota` → ProjectGroupController.java:275-280 → groupService.updateQuota） | 清空=不限 | 只接受 ≥0 数值；null→400「不限额度已停用（存量不限成员不受影响）」 |
| 复活既有邀请（ProjectGroupInviteService.java:102/:212 revive，快照随新邀请覆盖） | 可 null | 同邀请口径只接受数值 |
| 建组组长自动成员行（ProjectGroupController.java:47） | quota NULL | **不改**（决策 7 豁免：组长=预算归属人，NULL 是层级额度根语义） |

**池入组 UX 配套**：公共池审批通过后成员行落 0，审批操作返回话术/前端 message 提醒「该成员当前额度 0，请在成员表配额后可用」，避免「批完为何用不了」困惑。

**「不限」冻结语义**：`quota_limit_points IS NULL` 仍被全额预算代码尊重（存量行为不变），但成员行不再有任何 API 能写成 NULL（唯一例外：建组组长自动行，见上表豁免）；存量 null 行一旦被改成数值即永久数值化。前端对存量 null 行显示「不限（遗留）」。

**受影响分支核对清单**（全部确认行为正确，不需改逻辑，仅需回归验证）：
- MemberBudgetService.java:47-56（allocatable/reservedOf）：0 走正常预留计算。
- ProjectGroupWalletService.java:300-312、:552-564（doChargeGroup/预检）：quota=0 → used≥quota 入口即拦——正是期望（未配额不能用）。
- MediaGenTaskService.java:824-841（预估/硬拦）：avail=0 → 提交即拒，与修复设计「项目内剩余 quota−used」口径联动正确。
- ProjectGroupMemberMapper.java:50-55/73-76（countChildUnbounded/addUsed 放行）：0 非 NULL，新成员不计入「不限下级」防御分支、addUsed 不放行——收紧方向正确。
- ProjectGroupInviteService.java:84-94（被限额管理邀请必填限额）：默认 0 即「已填」，校验保留、语义不变。
- ProjectGroupService.java:571-591（降职缩额）：不受影响。
- 前端 5 处「不限」显示（ProjectGroupsView.vue:84/1090/1106/1159/428）：null 判断保留（存量仍显示），仅邀请弹窗与调额 prompt 文案改。

**单测新增**：MemberBudgetServiceTest 加「默认 0 成员消耗被拦 / 组长配额后放行 / 存量 null 行为不回归」三用例。

### 3.4 普通成员受限成员视图（17x-4）

**后端**：
- `getDetail`（ProjectGroupService.java:457-503）权限从 requireRole(MANAGER) 放宽为「组内任意成员（MEMBER 及以上）或 admin」；`GET /{id}` 与 `GET /{id}/overview` 两入口同口径，防绕过。
- 组装 members VO 时按 viewer 裁剪：viewer 为 MEMBER 且行非本人 → `quotaLimitPoints / usedPoints / selfPoints / debtPoolPoints / debtLeaderPoints / allocatablePoints / allocatedByUserId` 置 null；**本人行完整**；组长/MANAGER/admin 不裁剪。username/displayName/remark/role/joinedAt 保留（决策 5：组织信息可见）。
- **流水/审批/设置等其他管理接口维持 requireRole(MANAGER) 不动**。实现时必须逐接口核对 overview 聚合内容：若含流水/欠款等管理数据，同口径裁剪（探查未发现，标记为实现期必验项）。

**前端**（ProjectGroupsView.vue）：
- 成员 tab 对非 canManage 用户开放：受限列集（用户/角色/加入时间 + 本人行额度字段内联）；操作列与额度列（限额/已用/名下/欠款/可分配）隐藏。
- loadOverview 非 canManage 不再直接 return（:860-862），改调受限接口。
- 其余 tab（流水/审批/设置）v-if canManage 不变；产出 tab 不变；默认 tab 逻辑（:621）不变。
  【实现偏离记录（D4，P4 审查回补）】实现改为 openGroup 全员默认落「成员」tab（原 MEMBER 落产出 tab）——
  成员首屏即见组织信息（决策 6 意图更贴），管理视角无感知；行为良性，规格按此修订。

**信息暴露面声明**（安全评审依据）：本次放宽后普通成员新增可见=全组成员名单（用户名/姓名/角色/加入时间/备注）。额度类财务数据不可见。与 12x#3 displayName 口径一致。

## 4. 备注统计与审计（12x，2 项）

### 4.1 按备注汇总（12x-1，决策 4：独立视图 + 备注列）

**后端**：
- 新端点 `GET /api/billing/admin/remark-summary`（权限沿用 usage:view，BillingController）。SQL 走新 XML 查询（**LEFT JOIN**（左连接——左表行全保留、右表没匹配补 NULL）users 为主）：

```sql
SELECT COALESCE(u.remark, '') AS remark,
       COUNT(DISTINCT u.id)            AS user_count,
       SUM(b.balance_points)           AS balance_sum,
       COALESCE(SUM(r.recharge_points), 0)    AS recharge_points_sum,
       COALESCE(SUM(r.recharge_amount), 0)    AS recharge_amount_sum,
       COALESCE(SUM(g.consume_points), 0)     AS consume_points_sum,
       COALESCE(SUM(g.call_count), 0)         AS call_count
FROM users u
LEFT JOIN user_points_balance b ON b.user_id = u.id
LEFT JOIN (SELECT user_id, SUM(points_granted) recharge_points, SUM(amount_yuan) recharge_amount
           FROM payment_order WHERE status='PAID' GROUP BY user_id) r ON r.user_id = u.id
LEFT JOIN (SELECT user_id, SUM(points) consume_points, COUNT(*) call_count
           FROM llm_usage_logs GROUP BY user_id) g ON g.user_id = u.id
GROUP BY COALESCE(u.remark, '')
ORDER BY consume_points_sum DESC NULLS LAST
```

（表名/字段以实际实体为准：UserPointsBalanceEntity / PaymentOrderEntity.pointsGranted+amountYuan / llm_usage_logs；逻辑删除条件照既有 mapper 惯例补齐。）
- 现有四视图 SELECT 增 `u.remark`，VO 加 remark：by-user（LlmUsageLogMapper.xml groupByUser）、user-balances（UserPointsBalanceMapper）、adminRecharges（PaymentOrderMapper）、group-allocations（GroupAllocationMapper）。
- keyword 匹配扩 remark（LIKE 转义照 UserController:76-78 惯例）：PaymentOrderMapper:71-72/91-92、UserPointsBalanceMapper:72-73、GroupAllocationMapper:27-28/63-64。

**前端**（BillingAdminView.vue）：新「按备注」tab：备注（空显示「未填备注」）/ 人数 / 消耗积分 / 调用次数 / 充值积分 / 充值金额 / 余额合计，行首色条或 tag 区分；四视图行内加备注列（灰 tag，悬浮全文）。

**性能**：remark 无索引；admin 后台量级（单租户内部平台）一次聚合可接受，目标 <1s；如超，加 `users.remark` 索引或子查询限时段（预留手段，不在本期）。

### 4.2 审计列表显姓名/备注（12x-2，纯前端）

AuditLogView.vue:159-161 用户列改与详情弹窗（:38-46）同款：`username（operatorName）` + 备注 tag。已删用户 enrichOperatorMeta 查不到→自动回落 username 快照，无需处理。账号快照证据链设计（12x#2 已解决口径）不破坏。

## 5. 无限画布（2x，8 项）

### 5.1 两段式点击（C-1，决策 6：严格两段式）

- CanvasBoard.vue onNodeClick（:681-687）：删去选中后立即 emit preview-media 的分支，只选中。
- Lightbox 触发下沉到节点内媒体区：CanvasNodeBase → Image/Video 节点缩略图区加 click（stopPropagation），经 provide/inject（跨组件注入——父组件 provide 函数、任意子孙 inject 直接调用，免逐层透传；先例：resize 链 CanvasBoard.vue:523）通知开 Lightbox。
- 规则：媒体区点击时未选中→仅选中；已选中→开 Lightbox。节点其他区域点击→选中。
- Lightbox.vue / PropertyPanel 内三处 Lightbox 入口不动。

### 5.2 上游直显 + 类型配色（C-2）

- PropertyPanel.vue：删折叠交互（:45-49、showFarUpstream:934、切节点重置 watch:936），depth>1 与 depth=1 同区直显，保留 depth 层级号。
- KIND_BADGE 按类型配色（CSS 变量驱动：图片=青、视频=紫、文本=灰、音频=橙、脚本/分镜/导演=中性），缩略占位加类型图标。
- 上限 UPSTREAM_CAP=50 与截断提示（upstream.ts:17、PropertyPanel.vue:70）保留。

### 5.3 resize 手柄热区 + 四边（C-3）

- 覆盖 `.vue-flow__resize-control.handle`：视觉 8px + `::before` 透明热区扩至约 20×20（绝对定位 inset 负值）。
- 四边加 `variant="Line"` 控制，热区为节点边缘**内侧**窄带（约 8px）；连线 Handle（10×10，边缘中点）z-index 优先，错位共存。
- 若实测冲突：fallback=边线手柄仅选中态且鼠标贴近边缘 12px 时渐显。
- min 160/64 与落库链（onResizeEnd CanvasNodeBase.vue:91-96 + settle 补发 CanvasBoard.vue:847-860）不动——修复III C1 无回归。

### 5.4 自动保存补缺口（C-4）

三个缺口分别补：
1. **新增节点**：addNode（CanvasBoard.vue:591-609）内部统一 emit structure-changed（覆盖调色板/拖入两路）；CanvasView 快速加节点无连线分支（:2575-2586）补 scheduleSave。
2. **文本失焦**：MentionTextarea 新增 blur 通知（复用既有 onBlur :360-363 关候选层后的时机）→ PropertyPanel emit('data-changed')；名称框 onRenameBlur（PropertyPanel.vue:1163-1172）补保存。
3. **参数变更**：比例/时长/分辨率/audioMode 从 v-model 直绑改 @update:model-value 里 emit data-changed（:384/389/393/608），与既有离散选择器（:140-:300）同模式。

800ms 防抖 + 保存徽标（修复III C7）兜底高频触发。

### 5.5 分辨率整行（C-5）

PropertyPanel.vue:386-395 分辨率选择器独占整行（照 :381-385 比例先例）。与 5.6 面板调宽正向叠加。

### 5.6 面板拖宽（C-6）

面板左缘加 6px 拖拽条（hover 高亮），mousedown 拖改宽，clamp 260–560px，localStorage 持久化（键如 `canvas.propPanel.width`）；拖拽中 user-select:none + 阻断画布事件。窄屏回落 260。

### 5.7 新建即定型尺寸（C-7）

- addNode 对 **image/video** 节点预置 width=320 / height=320（与定型一致）；其余类型（文本/脚本/音频/分镜/导演）维持 200×自适应——它们没有「生成结果定型」语义。
- 定型分支（updateNodeData:989-999）保留：存量画布无 width/height 的节点仍走旧路径；新建节点因已有值不再触发，行为等效。
- types/canvas.ts:107-114 注释同步。

### 5.8 副本完全独立（C-8，决策 3）

nodeClone.ts RESET_KEYS 调整：
- **新增清除**：assetId / assetName / assetVersion（库引用断开）、taskId（入库判重脱钩）。
- **移入保留**（原在 RESET_KEYS）：fileId、previewUrl、coverPreviewUrl、outputText。
- **继续清除**：mediaTaskId、startedAt、finishedAt、errorMsg、assetHasUpdate、changeLog、localizeWarning。
- **status 规则**：有产物（previewUrl/outputText 任一）→ 'success'；否则 'idle'（running/failed 原件副本回落 idle，防幽灵轮询——重进画布恢复链 CanvasView.vue:2302-2314 只认 running+taskId）。
- firstFrameNodeId 保留原值（结构引用，指向原节点仍有效）。
- 再生不受影响：提交永远建新任务新 fileId（CanvasView.vue:1327-1332），不复用旧 taskId。

**独立后的边界**（写入手册）：副本再入库=全新首入库（判重键 (项目， taskId, imageIdx) 不再命中原件资产，「已入库」tag 不串显）；副本失去「重进画布按 taskId 重取预览」兜底（:2253/:2277-2280），预览 URL 失时效时靠再生成自愈。
**测试**：nodeClone.test.ts 重写断言（assetId/taskId 已清、产物四件保留、status 两分支）。

## 6. 数据模型与迁移

**无新表、无新列、无数据订正**。唯一 schema 触碰=无（17x-3 决策 1 明确不迁移存量）。12x-1 聚合走既有表 JOIN。

## 7. 安全与权限

| 项 | 内容 |
|---|---|
| 17x-4 放宽面 | getDetail/overview 对组内 MEMBER 开放+字段裁剪；暴露面=成员名单组织信息（见 §3.4 声明）；其他管理接口不动；实现期逐接口核对 overview 聚合内容 |
| 17x-3 越权封堵 | 调额度接口拒 null（「不限」不可再写入）；被限额管理邀请必填限额校验保留 |
| 17x-2b | 候选查询 requireOwner→requireRole(MANAGER)：读候选放宽到与发邀请同级，写权限不变 |
| 12x-1 | 新端点沿用 usage:view（admin-only），无新匿名面；keyword LIKE 转义照既有惯例 |
| C 域 | 全部纯前端，无权限变化 |

## 8. 测试策略

### 8.1 单元/组件测试
- UserPicker.test.ts：全选/反选/候选计数/程序获焦不弹。
- nodeClone.test.ts：重写（决策 3 断言）。
- PropertyPanel.test.ts：分辨率整行、参数变更 emit data-changed、上游直显。
- MemberBudgetServiceTest：默认 0 三用例（§3.3）。
- ProjectGroupService 测试：getDetail 三角色矩阵（组长全显/MEMBER 裁剪他人行+本人行全显/非成员 403）。

### 8.2 接口/curl 用例（本地实测，沿用 admin123 测试账号惯例）
- 17x-3 全链路：邀请不填限额→接受→成员 used/quota 均 0→消耗被拦→组长配额→放行；公共池申请→组长批准→成员行 0→配额前消耗被拦+提醒话术；调额度传 null→400；建组→组长自动行仍 NULL（豁免回归）。
- 17x-4：组长/MEMBER/admin 三账号分别打 overview+detail，核对裁剪。
- 12x-1：remark-summary 数字与 by-user 手工聚合对账；keyword=备注命中。

### 8.3 人工交互测试标记（自动化覆盖不了的手感类）
- C-1 两段式点击、C-3 手柄命中与四边/连线共存、C-6 拖宽手感。
- C-4 失焦保存（文本输入后直接关页签，重进验证）。
- 17x-2 弹窗打开不弹候选、点击搜索框才弹。

### 8.4 回归联动点清单（正向必验）
- 17x-3：预估/硬拦（quota−used=0 拒）、doChargeGroup 入口拦、countChildUnbounded 不再计新成员、降职缩额不回归、存量 null 成员行为不变。
- 17x-4：admin 代管 canManage 全显不回归；产出 tab 不变。
- C-8：组产出 tab「已入库」回填不受影响（taskId 判重按任务行，节点 data 无 taskId 不参与）。
- C-4：taskId 即时持久化链（CanvasView.vue:1329）不与新触发源打架。

## 9. 边界与不做

- **不迁移存量**；「不限」数值化后**不可逆**（不可回 null）。
- **公共池自助入组模型变更**（决策 7 的产品后果）：池入组成员从「进组即可用组池」变为「批准后需配额」，user-ops 手册增补必须写清；V156 相关注释同步更新。
- 全选只作用于已加载候选（≤50），不做全量服务端选择。
- C-8 副本舍弃 taskId 预览兜底（失时效靠再生成自愈）。
- C-3 若四边与连线手柄冲突不可调和，走 fallback（hover 渐显）。
- 12x-1 不做时段筛选/趋势（预留）。
- 三份问题文档勾销 + feature-map/user-ops「修复IV 增补」节：实现完成后的收尾批统一做（惯例同 III）。
- file_structure.md 无需更新（无新目录）。

## 术语表

| 术语 | 大白话 | 简单案例 |
|---|---|---|
| FOR UPDATE（行锁） | 数据库先把这行锁住，别人的写排队等 | 管理边花钱边分额度不会算错账 |
| LEFT JOIN（左连接） | 左表每行都保留，右表没匹配就补空 | 用户没充值也能出现在汇总里（金额 0） |
| GROUP BY（分组聚合） | 按某字段把行分堆后算合计 | 按备注分堆算每班总消耗 |
| Lightbox（灯箱） | 全屏遮罩大图预览 | 点图片放大查看 |
| VueFlow | 画布节点连线的前端库 | 无限画布本体 |
| provide/inject | 父组件放函数、子孙直接取用，免逐层传 | 画布板放「开灯箱」，节点直接调 |
| 热区（hit-area） | 看不见但点了算数的扩大点击范围 | 5px 手柄外套 20px 透明框好点中 |
| splitter（分隔条） | 拖它改变相邻区域宽度 | 拖宽右侧属性面板 |
| 防抖（debounce） | 连续触发只认最后一次，期间不执行 | 800ms 内连改参数只保存一次 |
| 悬空引用 | 指向已删除目标的引用 | 上游节点被删后引用失效 |
| 快照（snapshot） | 写入时刻的值留底，之后源变了不影响 | 审计存 username 快照防改名失联 |
