---
description: "子计划 D：用户备注 + 账单昵称/分配/对账 + 找回密码两修复 + 文档勾销（规格 §1、§6、§7、§8，Q6A/Q8/Q9A）"
created-date: 2026-08-25
---

# 子计划 D：账号、账单与认证修复

> 主索引：[人工测试遗留问题修复.plan.md](人工测试遗留问题修复.plan.md)
> 规格：§6（备注，Q6=A）、§8（账单，Q9=A）、§7.3（Q8 两实测问题）、§1/§10（文档勾销）

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| keyword LIKE 注入/通配符 | MyBatis `#{}` 预编译 + 前置转义 `%`/`_`；三字段 OR（username/name/remark） |
| 对账聚合把 MEMBER_* 算进资金等式 → 永久假警报 | SQL type 白名单 `IN ('ALLOCATE','RECLAIM','CONSUME','REFUND')`，单测钉死 |
| 对账大表全量 SUM 慢 | `SUM() GROUP BY group_id` 单查 + 评估 `(group_id, type)` 索引；默认时间窗（近 90 天）+ 全量开关 |
| 「累计被分配」聚合口径混 | 毛额=Σ MEMBER_ALLOCATE，净额=毛额−Σ MEMBER_RECLAIM；两列分开展示（规格 §8.3.3） |
| reset 页修完仍露 SMS 死路 | 渠道判定同时看 `GET /api/auth/channels` 的 smsEnabled 与 URL 参数；SMS 未启用分支整体不渲染 |
| 昵称三处只补前端漏后端 | 每处 = 后端 SQL/VO + 前端列 成对改；name 缺省回退 username |

## 实现步骤

- [ ] **D1：用户备注（Q6=A：本人+管理员可改）**
  - **目标**：注册/个人信息可填；管理列表可筛可见
  - **动作**：
    - 注册：`RegisterRequest` 增 `remark`（@Size≤64 可选）；`AuthService.registerInternal` 落库；`RegisterModal.vue` 输入框「如：A 班（选填）」
    - 资料：`UpdateProfileRequest` 增 `remark`（≤128）；`ProfileSettingsTab.vue` 「备注」项；`stores/auth.ts` updateProfile 透传
    - 管理：`GET /api/users`（UserController:42-59）增 `keyword`（三字段模糊 OR）/`status` 参数；返回 VO 增 `name`/`remark`；`UserManageView.vue` 搜索框+状态筛选+备注列
  - **文件**：`auth/dto/RegisterRequest.java`、`auth/service/AuthService.java`、`auth/dto/UpdateProfileRequest.java`、`user/controller/UserController.java`、`frontend/src/views/login/RegisterModal.vue`、`frontend/src/components/settings/ProfileSettingsTab.vue`、`frontend/src/views/admin/UserManageView.vue`、`frontend/src/api/admin.ts`
  - **依赖**：B1（V157 remark 列）
  - **安全检查**：user:manage 权限不变；LIKE 转义；长度校验
  - **验证**：人工——注册带「A 班」→ 管理列表 keyword「A 班」命中；本人改备注即时反映

- [ ] **D2：账单昵称三处**
  - **目标**：用户余额/充值记录/用户排行 显示 name（username）
  - **动作**：user-balances 与 recharges 的 SQL/VO 增 name（JOIN users）；排行 tab 同理（user_id → 补 username+name）；`BillingAdminView.vue` 三 tab 用户列改 `name（username）`；keyword 筛选同步匹配 name
  - **文件**：`billing/controller/BillingAdminController.java`、对应 Mapper SQL、VO（`UserBalanceRowVO.java` 等）、`frontend/src/views/admin/BillingAdminView.vue`
  - **依赖**：无
  - **验证**：三 tab 均见昵称；筛 name 命中

- [ ] **D3：项目组分配视图（20x-2）**
  - **目标**：各用户各项目 quota/used/剩余/累计被分配（毛额+净额）
  - **动作**：新端点 `GET /billing/admin/group-allocations`：
    ```
    行 = project_group_members 活行 JOIN users JOIN project_group
    列 = name, username, 组名, quota, used, 项目内剩余(quota−used),
         累计被分配(Σ ledger MEMBER_ALLOCATE by member), 净额(毛额−Σ MEMBER_RECLAIM), 最近分配时间
    筛选 = keyword(用户)/groupId；分页
    ```
    `BillingAdminView.vue` 新「项目组分配」tab
  - **文件**：`BillingAdminController.java`、新 Mapper 查询、`BillingAdminView.vue`、`frontend/src/api/admin.ts`（或 billing api）
  - **依赖**：A1（MEMBER_* 流水已有数据）
  - **验证**：人工——A 计划人工测试后，该 tab 累计被分配 = 历次调增之和

- [ ] **D4：划拨对账（20x-3，Q9=A）**
  - **目标**：顶卡「账平/不平」+ 仅列异常组
  - **动作**：新端点 `GET /billing/admin/group-reconcile`：
    ```
    每组: 划入净额=Σ(ALLOCATE)−Σ(RECLAIM); 消耗=Σ(CONSUME); 退款=Σ(REFUND)   // type 白名单，排除 MEMBER_*
    恒等式: 划入净额+退款−消耗 vs 组池当前余额 → 差值
    响应: {总体: {划拨净额合计, 消耗合计, 退款合计, 余额合计, 平/不平}, 异常组: [组名, 各值, 差值]}
    附: 双账本交叉校验（组账本 vs points_ledger GROUP_* 腿）
    ```
    `BillingAdminView.vue` 新「项目组对账」tab：顶部状态卡 + 异常组表
  - **文件**：`BillingAdminController.java`、`projectgroup` 相关 Mapper 聚合查询、`BillingAdminView.vue`
  - **依赖**：无（对账只读现有流水）
  - **验证**：人工——走 划拨→消耗→退款→回收 流程后恒等式平；手工 UPDATE 组池 −10 → 该组标红

- [ ] **D5：找回密码两修复（Q8）**
  - **目标**：QQ 邮箱链接可复制兜底；直达 reset 页不再落 SMS 死路
  - **动作**：
    - 邮件模板（`EmailService.sendResetEmail` :93-114）：链接按钮下方加纯文本 URL +「若点击无效，请复制此链接到浏览器打开」
    - `ResetPasswordView.vue` onMounted（:163-175）重写：
      ```
      channels = await authApi.channels()
      ch==SMS 且 channels.smsEnabled → SMS 表单
      token 且 channels.emailEnabled → EMAIL 表单
      无 token → EMAIL 启用: 引导页「请通过邮件中的重置链接进入」+「重新发送重置邮件」按钮(跳 /login 唤起忘记密码弹窗)
                EMAIL 未启用: 提示「请联系管理员重置」
      ```
  - **文件**：`auth/service/EmailService.java`、`frontend/src/views/ResetPasswordView.vue`
  - **依赖**：无
  - **验证**：人工——发重置邮件见纯文本链接可复制打开；无 token 直达显示引导页（不再露手机号表单）；带 token 正常重置

- [ ] **D6：文档勾销与漂移清理（§1.3、§7、§10）**
  - **动作**：
    - 7 份人工测试文件未解决项逐条勾销（注明对应规格章节）
    - `UserPointsBalanceEntity` 「可负」注释（B1 已顺手改，此处核销）；`docs/specs/积分计费系统.md` §B6 改口径并记原因
    - 更新 feature-map / user-ops：积分计费系统（HOLD/DEBT）、项目组与积分划拨（缩额/成员流水/对账）、认证（备注/reset 页）；速查表 `08-智能对话与流式.md`（PROGRESS/usage）——9x 文件明确要求
  - **文件**：`workflow_output/人工测试问题/*.md`（7 份）、`docs/specs/积分计费系统.md`、`docs/feature-map/*` 3-4 份、`docs/user-ops/*` 2-3 份、`项目工程文档/项目功能介绍/速查表/08-智能对话与流式.md`
  - **依赖**：A、B、C、D1-D5 全部完成
  - **验证**：逐条对照规格总览表 §0，12 项全部闭环

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 备注（注册/资料/管理员）变更 | 管理列表备注列+keyword 筛选 | 即时命中 | 空备注筛不到（预期）；超长被 400 拦 |
| 成员配额变动（A 计划流水） | D3 分配视图毛额/净额 | 增长/回减 | 调减不动毛额只动净额 |
| 任何组资金流水 | D4 对账恒等式 | 始终平 | 手工改库/bug → 标红 |
| 欠款产生（B 计划） | 钱包页/充值页提示 | 显示欠款 | B 计划内实现，此处仅文档联动 |
| EMAIL 通道开关切换 | reset 页分支 / 忘记密码 radio | 同步显隐 | 两处读同一 channels 接口 |

## 验证收口

- [ ] D1-D6 全绿；规格 §0 总览 12 项全部闭环可勾销
