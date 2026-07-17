# 互动式 AI 工作助手 — 总进度

## 总体状态

| 阶段 | 状态 | 进度 | 备注 |
|------|------|------|------|
| Phase 1 | 🟢 已完成 | 100% | MVP：IM 入站 + Inbox + 飞书固定工作完成；后端 Task 1–13、前端 Task 14–17、测试验证 Task 18–21 全部完成 |
| Phase 2 | 🟢 已完成 | 100% | NLP/灵感随记/多平台 webhook/IM 确认回复/日期化固定工作全部完成 |
| Phase 3 | 🟢 已完成 | 100% | AI 报告增强全部任务完成 |
| Phase 4 | 🟢 已完成 | 100% | 体验优化全部任务完成；后端 131 个测试、前端 247 个测试全部通过 |

## 各阶段入口

- [Phase 1 进度](phase1/progress.md)
- [Phase 2 进度](phase2/progress.md)
- [Phase 3 进度](phase3/progress.md)
- [Phase 4 进度](phase4/progress.md)

## 最近更新

- 2026-07-18：修复登录账号 15 分钟后被提示「未登录」的隐患
  - 问题根因：后端 `UserAuthService.createAuthResponse` 把 `expiresInSeconds` 硬编码为 `15 * 60`，但 `JwtService` 实际给普通用户签发的 access token 是 24 小时。前端据此每 15 分钟做一次 token 刷新，一旦某次刷新因网络/Redis 等原因失败，用户就会被踢到未登录状态。
  - 修复文件：`server/src/main/java/com/superprogrammer/user/service/UserAuthService.java`
  - 修复方式：根据用户角色从 `AuthProperties` 读取对应过期时间返回：普通用户 24 小时（`clientAccessTokenHours * 3600`），超管 15 分钟（`accessTokenMinutes * 60`）。
  - 测试覆盖：`ClientAuthorizationTest` 验证普通用户登录返回 86400 秒；`AdminAuthControllerTest` 验证超管登录返回 900 秒。
  - 测试结果：`ClientAuthorizationTest`（4 通过）、`AdminAuthControllerTest`（3 通过）。
  - Commit：`dc61625`
- 2026-07-18：修复未登录时工作助手模块周期性报「未登录」bug
  - 问题根因：`InboxPanel.vue` 挂载后每 30 秒轮询 `loadInbox()`，且 `WorkReportManagement.vue` 挂载即调用 `loadToday()`；两者均通过 `workReportStore.getAuthContext()` 强依赖 `accessToken`，未登录时直接抛错并在 UI 顶部红色提示。
  - 修复文件：`src/components/work-report/InboxPanel.vue`、`src/components/work-report/WorkReportManagement.vue`
  - 修复方式：在 `onMounted` 中先判断 `authStore.isAuthenticated`，未登录时直接返回，不触发需要认证的 API 调用与轮询。
  - 测试结果：`workReportStore.test.ts`（12 通过）、`workReport.test.ts`（18 通过）、`authStore.test.ts`（14 通过）、`authDialog.test.ts`（6 通过）。
  - Commit：`74b241b`
- 2026-06-30：Phase 4 全部任务完成，后端 131 个测试、前端 247 个测试全部通过
- 2026-06-29：Phase 3 全部任务完成，后端 114 个测试、前端 247 个测试全部通过
- 2026-06-29：Phase 2 全部任务完成，后端 108 个测试、前端 244 个测试全部通过
- 2026-06-28：完成计划拆分与进度文件创建
- 2026-06-28：前端 Task 14–17 完成；发现后端 Task 1–13 实际未实现，已补齐后端全部实现
- 2026-06-28：Phase 1 全部 21 个任务完成，后端 108 个测试、前端 244 个测试全部通过
- 2026-06-28：输出 Phase 1 端到端手动验证清单
