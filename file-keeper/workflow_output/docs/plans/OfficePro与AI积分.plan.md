# Office Pro 与 AI 积分实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: `phase3-implement`。本轮不做支付系统。

**目标：** 免费额度本地可用；超额任务实时校验 Office Pro；管理员授予套餐和调整 AI 积分；AI 用量可预扣、结算和防重复。

---

### Chunk 1：服务端数据迁移与实体

- [ ] **目标：建立套餐和积分事实源。**
  - 动作：新增连续 Flyway 迁移；建立 subscription、wallet、ledger、usage、monthly_grant 实体/Repository；整数积分和乐观锁。
  - 涉及文件：`server/src/main/resources/db/migration/V17__add_office_subscription.sql`、`V18__add_office_ai_wallet_and_ledger.sql`、`server/src/main/java/com/superprogrammer/office/entity/OfficeSubscription.java`、`server/src/main/java/com/superprogrammer/officeai/entity/*`、对应 `repository/*`、测试迁移。
  - 依赖：确认当前最高迁移仍为 V16；若 Phase 3 前增加迁移，顺延版本号。
  - 伪代码：`wallet available>=0; ledger request_id unique; one active subscription per user`。
  - 验证：PostgreSQL/H2 兼容；并发更新不出现负余额。

### Chunk 2：Office Pro 实时校验

- [ ] **目标：超额任务每次联网验证，不生成离线 Pro Token。**
  - 动作：新增查询与任务票据接口；校验 JWT、active device、subscription、任务摘要和幂等键；票据短时且绑定 taskId/用户/规模摘要。
  - 涉及文件：`server/src/main/java/com/superprogrammer/office/controller/OfficeEntitlementController.java`、`service/OfficeEntitlementService.java`、`dto/OfficeTaskAuthorizationRequest.java`、`dto/OfficeTaskAuthorizationResponse.java`、测试文件、`src/api/officeEntitlement.ts`、`src/stores/officeEntitlementStore.ts`。
  - 依赖：Chunk 1、统一底座扫描结果。
  - 伪代码：`if within free -> client executes locally; else POST summary -> validate -> short task ticket`。
  - 验证：断网/过期/撤销/设备禁用均拒绝；重放其他 taskId 失败；不上传路径和正文。

### Chunk 3：AI 钱包事务

- [ ] **目标：准确预扣、结算、释放且可重试。**
  - 动作：实现 WalletService、LedgerService、月度赠送幂等任务；使用数据库事务、version 和 requestId。
  - 涉及文件：`server/src/main/java/com/superprogrammer/officeai/service/OfficeAiWalletService.java`、`OfficeAiGrantService.java`、DTO、JUnit 测试、`server/src/main/java/com/superprogrammer/settings/SettingKeys.java`。
  - 依赖：Chunk 1。
  - 伪代码：`reserve -> provider call -> settle(actual) OR release; retry same request returns prior result`。
  - 验证：100 并发请求余额不负、账本平衡、重复 requestId 不二次扣费。

### Chunk 4：管理员套餐与积分入口

- [ ] **目标：不用支付系统也能安全授予 Office Pro 和积分。**
  - 动作：增加方法权限、Controller、审计；管理后台用户详情增加套餐状态、到期时间、授予/撤销和积分调整对话框；变更原因必填、键盘可操作。
  - 涉及文件：`server/src/main/java/com/superprogrammer/admin/controller/AdminOfficeController.java`、`service/AdminOfficeService.java`、DTO、测试、`admin-web/src/api/office.ts`、`admin-web/src/views/UserDetailView.vue`、`admin-web/src/types/index.ts`、路由/权限配置。
  - 依赖：Chunk 1、3。
  - 验证：无权限 403；所有变更有 before/after/reason；撤销后新超额任务立即失败。

### Chunk 5：客户端额度体验

- [ ] **目标：功能始终可见，只在开始超额任务时要求登录/Pro。**
  - 动作：显示免费额度、当前汇总、Pro 状态和升级说明；从超额删文件降回免费时清除票据；AI 积分独立显示。
  - 涉及文件：`src/components/office/OfficeQuotaBanner.vue`、`OfficeProDialog.vue`、`src/stores/officeEntitlementStore.ts`、`src/locales/zh-CN.ts`、`src/locales/en.ts`、测试。
  - 依赖：Chunk 2。
  - 验证：100/101、1GB 边界、100MB 单文件边界；登录不等于 Pro；AI 积分耗尽不禁用本地任务。

### Chunk 6：运维与检查点

- [ ] 增加 Pro 校验延迟/拒绝率、钱包异常、月度赠送失败指标和告警；加入 Office 权益/积分开关；运行 `mvn test`、桌面/后台测试并提交存档点。

## 安全与坑点

- 不复用废弃 `user_module_entitlements` 或离线 Token；新套餐域独立。
- 任务票据只证明一次超额任务已在线校验，不能作为长期凭据。
- 管理员调整积分使用补偿账本，禁止直接改余额不留痕。
- 客户端规模摘要不可信；Rust 本地必须再次校验实际输入清单。

## 术语表

| 术语 | 大白话 | 示例 |
|---|---|---|
| 预扣 | 调模型前先冻结预计额度 | 防止并发把余额花成负数 |
| 补偿账本 | 用新记录纠正旧结果 | 管理员加回误扣积分 |
