---
description: "子计划 B：审计全量补齐（§7，Q5=B：P0 手工行 + 字典对齐 + P1/P2/P3 注解全上）"
created-date: 2026-08-26
---

# 子计划 B：审计全量补齐

> 主索引：[人工测试遗留问题修复II.plan.md](人工测试遗留问题修复II.plan.md)
> 规格：§7（8x-1 邮件审计 / 8x-2 字典英文残留 / 8x-3 漏审计 sweep，Q5=B 一次全上 P0+P1+P2+P3）。

## 技术坑点预判

| 坑 | 规避 |
|---|---|
| P0 端点公开无 JWT——AOP 走 MDC 拿不到身份 | 全走 `AuditLogService.fromMdc` 手工建行（AuthService.auditAuth 先例：显式传 userId/username/IP）；detail 手拼 JSON 不经 LogMasker |
| 手工行 detail 拼接手写 JSON 出转义 bug | 复用既有 ObjectMapper 序列化 Map（不手拼字符串） |
| 字典补了后端漏了前端（或反之） | 字典完整性单测：遍历「已知全部 module:action 码集」（常量表驱动）断言字典 100% 命中；前端 moduleOptions 与后端 MODULE_LABEL 键集对齐断言（单测读两边常量不可行——改为前端单测断言 18 项与 detailLabels 键一致） |
| AOP 自动采参把密钥/大对象进 detail | LogMasker apiKey 类关键词已打码——UserLlmController 实现期核对命中；DTO 参数 String.valueOf 截 200 字符天然防爆 |
| P2 画布编辑类高频端点刷爆审计表 | frames/crop/transform/clip/concat 等编辑端点注解 targetType=canvas/asset 只记 id 类参数——AOP 采参是全参数，detail 仍会带；接受（截断 200 字符）+ 页面高频操作实测量有限（单人编辑频率），若 QPS 异常降级为仅 FAIL 行（回规格 §7.3 记录） |
| 忘删死注释（EmailVerifyController 骗人注释） | B1 内显式清理两处 javadoc |
| P0 在 service 层手工建行会重复记（Controller 若再加注解） | P0 八动作**只**走手工行，对应 Controller 不加注解；代码注释写明防后人双记 |

## 实现步骤

- [x] **B1：P0 手工审计行（邮件/短信/微信/MFA，8x-1 本体）**（commit 1e724fd5，单测 15/15，全量回归 2490/2490）
  - **目标**：触发邮件/短信/微信/MFA 的动作全进审计，记 IP+邮箱/手机号
  - **动作**：
    ```
    九处手工建行（fromMdc(module,action,targetType,targetId,detail,result)，
    detail = ObjectMapper.writeValueAsString(Map.of("email",..., "ip",..., "reason"...))）：
      EmailService.sendRegisterCode   → auth:send_register_code {email, ip}（成功+失败/限流分支都记）
      EmailService.resendVerifyEmail  → auth:resend_email        {email, ip}
      EmailService.verifyEmail        → auth:email_verify        {email(从token解出), ip}
      PasswordResetService.forgot     → auth:password_forgot     {identifier原文, ip, hit:是否命中账号}
      PasswordResetService.reset      → auth:password_reset      {userId, ip}
      SmsService 发码                 → auth:sms_code_send       {phone, ip}
      SmsService 短信登录成功         → auth:sms_login           {phone, ip}
      WechatAuthService 回调成功      → auth:wechat_login        {userId或openid, ip}
      MfaService bind/bindConfirm/unbind → auth:mfa_bind / mfa_bind_confirm / mfa_unbind {userId, ip}
    清理 EmailVerifyController.java:36、:47 死注释
    ```
  - **文件**：`auth/service/EmailService.java`、`auth/service/PasswordResetService.java`、`auth/service/SmsService.java`、`auth/service/WechatAuthService.java`、`auth/service/MfaService.java`、`auth/controller/EmailVerifyController.java`（注释）
  - **依赖**：无
  - **验证**：单测各动作 SUCCESS/FAIL 两分支建行（mock AuditLogService 断言参数）；人工——注册页发码→审计出现完整邮箱+IP

- [x] **B2：字典补齐（8x-2 后端侧）**（17 模块 168 码全量进字典 + 完整性测试锁基线）
  - **目标**：18 模块+全部动作码中文 100% 覆盖
  - **动作**：
    ```
    AuditLabelDictionary：
      MODULE_LABEL +4：security=安全管理, feedback=公告建议台, project-group=项目组, audit=审计链
      注释「13 模块」→「18 模块」
      ACTION_LABEL 补齐（存量缺失约 40 条 + P0 新码 3 条 + P1/P2/P3 新码，见规格 §7.2 三张清单
      + B4/B5/B6 注解时新增的动作码，实现期随注解一起进字典）
    新增单测 AuditLabelDictionaryCompletenessTest：
      KNOWN_CODES 常量集（硬编码全部 module:action）遍历断言 label 非空非码本身
    ```
  - **文件**：`common/audit/AuditLabelDictionary.java`、`common/audit/AuditLabelDictionaryCompletenessTest.java`（新）
  - **依赖**：与 B4-B6 同步累进（每加一批注解同步进字典+常量集）
  - **验证**：单测绿；审计页抽查 security/project-group/feedback 模块行中文

- [ ] **B3：前端对齐（8x-2 前端侧）**
  - **目标**：模块下拉 18 项、detail key 中文
  - **动作**：
    - `AuditLogView.vue:114-128` moduleOptions 补 file/security/feedback/project-group/audit（删幽灵码或后端补码——实现期与 B2 常量集对表，workflow/points/project 后端无写入则前端删）
    - `detailLabels.ts` 补新 detail key（email/phone/identifier/reason/hit 等缺失项）；DETAIL_KEY_CN 与后端 detail 字段对齐
  - **文件**：`frontend/src/views/admin/logs/AuditLogView.vue`、`frontend/src/utils/detailLabels.ts`
  - **依赖**：B2
  - **验证**：前端单测（若有）/人工——下拉 18 项全中文；P0 新行 detail 键值中文渲染

- [x] **B4：P1 注解补齐（管理端敏感写）**（7 Controller 38 注解；新模块 workflow；字典+常量集+前端 19 模块同步）
  - **目标**：部门/Provider/工作流/审批/智能体/用户级密钥/搜索测试全进审计
  - **动作**（各端点加 `@AuditLog(module, action)`，动作码同步进字典与 B2 常量集）：
    ```
    DepartmentController：dept_create/dept_update/dept_delete/dept_member_add/dept_member_remove（module=user 或新 dept——用现有 user 模块）
    LlmController：provider_create/provider_update/provider_delete/provider_test/provider_reload
    WorkflowController：workflow_create/update/delete/duplicate/import/kb_binding_set
    ExecutionController：execution_approve/reject/retry/resume/input_submit
    AgentController：agent_create/update/delete/copy/permission_set/skill_set/sync
    UserLlmController：user_provider_save/user_provider_delete/user_provider_test（LogMasker apiKey 核对）
    SystemSettingController：web_search_test
    ```
  - **文件**：上列 7 个 Controller
  - **依赖**：B2（码进字典）
  - **验证**：人工抽查每 Controller 一端点 → 日志在、码中文

- [x] **B5：P2 注解补齐（用户资源写）**（10 Controller 47 注解；新模块 project；Payment create/cancel 服务层已审防双记）
  - **目标**：画布/资产/项目/文件/会话/知识库建/支付 mock 全进审计
  - **动作**：
    ```
    CanvasController：canvas_create/update/rename/delete/版本三/frame_write/crop/transform/clip/concat
      （编辑类 detail 靠 AOP 截断，见坑点表）
    AssetController：asset_create/update/delete/版本/lock/unlock/archive/unarchive/包/分镜/breakdown
    AssetProjectController：project_create/update/delete/transfer/settings_update
    AssetMemberController：project_member_add/update/remove
    AssetCanvasBridgeController：canvas_import/resolve
    ProjectController：project_create/update/delete/member_add/member_remove
    FileController：file_download（GET 下载补注解——8x 上期已有 3 下载先例，此处补漏）
    ChatController：session_create/delete/target_update
    KnowledgeBaseController：kb_create
    PaymentController：mock_trigger
    ```
  - **文件**：上列 10 个 Controller
  - **依赖**：B2
  - **验证**：人工抽查画布新建/资产删除/文件下载 → 日志在

- [ ] **B6：P3 注解补齐（记忆模块写）**
  - **目标**：记忆标签/条目/配置/整合/项目规则写操作进审计
  - **动作**：MemoryTag（tag_create/update/reclassify）、MemoryEntry（entry_review/entry_delete）、MemoryGenConfig（gen_config_set）、MemoryConsolidation（consolidation_trigger/auto/resolve）、MemoryProjectRule 各写端点加注解
  - **文件**：上列 5 个 Controller
  - **依赖**：B2
  - **验证**：人工抽查改标签 → 日志在

- [ ] **B7：收口验证**
  - sweep 复查：grep 全 Controller 写方法（POST/PUT/DELETE）无注解且不在免审清单 → 清零或列入免审理由
  - 人工：规格 §7.5 四项全过；审计页无任何英文模块/动作显示（抽 50 行）

## 功能联动点清单

| 触发 | 联动对象 | 预期 | 边界 |
|---|---|---|---|
| 发码被限流拒（C 计划改语义） | 审计行 | result=FAIL + reason=RATE_LIMIT | 不与滑块计数互通（两系统独立） |
| P0 手工行 | 审计页用户列 | 无 JWT 时 userId 可空/由 detail 补 | 显示层兜底 `用户#id/系统` 已有 |
| P2 编辑高频操作 | 审计表容量 | detail 截断 200 字符 | QPS 异常降级仅 FAIL 行（回规格记录） |
| B2 字典新增 | 前端下拉/详情 | 18 项中文 | 幽灵码删除前后端同步，防再分叉 |

## 验证收口

- [ ] B1-B7 全绿；8x 三项可勾销（邮件审计在、无英文模块、sweep 清零）
