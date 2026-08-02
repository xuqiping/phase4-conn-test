# Phase 1 进度 — IM 入站 + Inbox + 飞书固定工作完成

**状态：** 🟢 已完成  
**总任务数：** 21（已完成 21）  
**详细计划：** [plan.md](plan.md)  
**手动验证清单：** [phase1-manual-test.md](../phase1-manual-test.md)

## 后端

- [x] Task 1: 数据库迁移 `V12`（原计划 V10，因 file-keeper/server 实际最高为 V11 而调整）
- [x] Task 2: 新增枚举与 DTO
- [x] Task 3: `InboundMessage` 实体与 Repository
- [x] Task 4: 规则 NLP 意图识别服务
- [x] Task 5: 飞书 Webhook 适配器
- [x] Task 6: 扩展 `FixedWorkCompletion` 与 Repository
- [x] Task 7: 扩展 `FixedWorkService` 支持按名称标记完成
- [x] Task 8: 扩展 `PushTargetRepository` 按平台与目标 ID 查询
- [x] Task 9: `InboundMessageService` 业务编排
- [x] Task 10: 扩展 `WorkLog` 实体与 Repository
- [x] Task 11: 飞书 Webhook Controller
- [x] Task 12: InboundMessage Controller
- [x] Task 13: SSE 事件推送服务与 Controller

## 前端

- [x] Task 14: 前端类型与 API
- [x] Task 15: Store 扩展
- [x] Task 16: `InboxPanel.vue` 组件
- [x] Task 17: 集成到 `WorkReportManagement.vue`

## 测试与验证

- [x] Task 18: 新增接口授权测试（`InboundMessageControllerAuthTest`、`WorkReportEventControllerAuthTest`）
- [x] Task 19: 后端编译与测试（`mvn test` 108 个测试全部通过）
- [x] Task 20: 前端编译与类型检查（`vue-tsc --noEmit` 通过，`npm test` 244 个测试全部通过）
- [x] Task 21: 端到端手动验证准备（已输出 [phase1-manual-test.md](../phase1-manual-test.md)）

## 里程碑

- [x] 飞书群消息能写入 `inbound_messages`
- [x] 桌面端 Inbox 能看到待确认消息
- [x] 确认后固定工作完成状态被正确标记
- [x] 未授权用户访问新接口返回 403
- [x] 后端测试通过、前端类型检查通过

## 实施适配说明

1. **迁移版本**：原计划 `V10`，因 `file-keeper/server` 现有迁移最高为 `V11`，实际使用 `V12`。
2. **推送目标 Repository**：当前代码库同时存在 `ReportPushTargetRepository`（旧）与 `PushTargetRepository`（新）。Task 8 使用新的 `PushTargetRepository`，因其已包含 `user_id` 字段，可直接反查消息归属用户。
3. **进度文件修正**：原总进度文件记录后端 Task 1–13 已完成，但实际代码中相关文件不存在，因此在实施过程中重新补齐。
4. **用户映射链路**：MVP 阶段通过 `push_targets(user_id, platform, target_id)` 直接定位用户，无需再经过 `report_configs`。
5. **授权测试断言**：项目中客户端模块授权统一返回 `R.fail(ErrorCode.FORBIDDEN)`（HTTP 200，body code 403），因此 Inbox 授权测试检查 `$.code` 为 403；SSE 端点未授权时抛出 `BusinessException`，由全局异常处理器返回 HTTP 403。
6. **前端轮询**：MVP 阶段 InboxPanel 使用 30 秒轮询，后端 SSE 已就绪，后续可替换为 EventSource。
