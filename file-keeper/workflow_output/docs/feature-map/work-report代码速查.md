# 工作汇报模块代码文件速查表

> 按功能维度快速定位工作汇报模块（`work-report`）涉及的代码文件。

---

## 一、数据层（数据库表 + 实体）

| 功能 | 数据库表 | Flyway 迁移 | Java 实体 |
|---|---|---|---|
| 工作记录 | `work_logs` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) | [`WorkLog.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/WorkLog.java) |
| 每日安排 | `work_plans` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) + [`V7__enhance_work_plans.sql`](../../../server/src/main/resources/db/migration/V7__enhance_work_plans.sql) | [`WorkPlan.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/WorkPlan.java) |
| 报告模板 | `report_templates` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) | [`ReportTemplate.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/ReportTemplate.java) |
| 报告规则配置 | `report_configs` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) | [`ReportConfig.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/ReportConfig.java) |
| 推送目标 | `report_push_targets` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) | [`ReportPushTarget.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/ReportPushTarget.java) |
| 已生成报告 | `work_reports` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) | [`WorkReport.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/WorkReport.java) |
| 推送记录 | `push_deliveries` | [`V6__add_work_report_module.sql`](../../../server/src/main/resources/db/migration/V6__add_work_report_module.sql) | [`PushDelivery.java`](../../../server/src/main/java/com/superprogrammer/workreport/entity/PushDelivery.java) |

---

## 二、后端：客户端接口（桌面端调用）

| 功能 | 入口 Controller | 主要 Service |
|---|---|---|
| 工作记录 CRUD、每日安排 CRUD、报告生成/历史/推送 | [`WorkReportClientController.java`](../../../server/src/main/java/com/superprogrammer/workreport/controller/WorkReportClientController.java) | [`WorkLogService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/WorkLogService.java)、[`WorkPlanService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/WorkPlanService.java)、[`WorkReportService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/WorkReportService.java) |
| 报告模板管理 | 同上 | [`ReportTemplateService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/ReportTemplateService.java) |
| 报告规则配置管理 | 同上 | [`ReportConfigService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/ReportConfigService.java) |
| 模块授权校验 | 由 Spring Security + 拦截器统一处理 | 授权核心见 [`commercialAuth.ts`](../../../src/api/commercialAuth.ts) / [`commercialAuthStore.ts`](../../../src/stores/commercialAuthStore.ts) |

---

## 三、后端：管理后台接口

| 功能 | 入口 Controller | 说明 |
|---|---|---|
| 用户模块权益授予/撤销 | [`AdminEntitlementController.java`](../../../server/src/main/java/com/superprogrammer/admin/controller/AdminEntitlementController.java) | 给用户开通/关闭 `work-report` 模块授权 |
| 用户设备查看/禁用 | [`AdminDeviceController.java`](../../../server/src/main/java/com/superprogrammer/admin/controller/AdminDeviceController.java) | 查看用户绑定的桌面端设备 |
| 匿名设备试用管理 | [`AdminAnonymousDeviceController.java`](../../../server/src/main/java/com/superprogrammer/admin/controller/AdminAnonymousDeviceController.java) | 管理匿名设备的试用状态 |
| 系统设置 | [`AdminSettingsController.java`](../../../server/src/main/java/com/superprogrammer/admin/controller/AdminSettingsController.java) | 含默认设备上限、离线缓存、匿名试用天数等 |
| 仪表盘统计 | [`AdminStatsController.java`](../../../server/src/main/java/com/superprogrammer/admin/controller/AdminStatsController.java) | 管理后台首页数据 |

---

## 四、后端：AI 总结、定时调度、推送、提醒

| 功能 | 代码文件 | 说明 |
|---|---|---|
| AI 总结服务 | [`AiSummaryService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/AiSummaryService.java) | 调用大模型把记录整理成报告文本 |
| 报告模板渲染 | [`ReportTemplateEngine.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/ReportTemplateEngine.java) | 将模板 + 数据渲染为最终 Markdown |
| 定时生成报告 | [`ReportScheduleService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/ReportScheduleService.java) | 按 cron 表达式触发报告生成 |
| 固定工作/未来计划提醒调度 | [`ReminderScheduleService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/ReminderScheduleService.java) | 每分钟扫描并触发到期提醒 |
| 提醒调度单元测试 | [`ReminderScheduleServiceTest.java`](../../../server/src/test/java/com/superprogrammer/workreport/service/ReminderScheduleServiceTest.java) | 月度 31 号 fallback 等用例 |
| 飞书推送 | [`FeishuPusher.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/push/FeishuPusher.java)、[`ReportPushServiceImpl.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/ReportPushServiceImpl.java) | 飞书 webhook 推送 + 推送记录落库 |
| 推送重试 | [`PushRetryService.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/PushRetryService.java) | 失败推送的重试逻辑 |
| 推送凭据加密 | [`CredentialEncryptor.java`](../../../server/src/main/java/com/superprogrammer/workreport/service/CredentialEncryptor.java) | 加密存储飞书 webhook key 等敏感信息 |

---

## 五、后端：登录态与 Token 有效期

| 功能 | 代码文件 | 说明 |
|---|---|---|
| JWT 生成 | [`JwtService.java`](../../../server/src/main/java/com/superprogrammer/security/JwtService.java) | 按角色分别设置 15 分钟 / 24 小时有效期 |
| 认证配置 | [`AuthProperties.java`](../../../server/src/main/java/com/superprogrammer/config/AuthProperties.java) | `accessTokenMinutes` / `clientAccessTokenHours` |
| 配置文件 | [`application.yml`](../../../server/src/main/resources/application.yml) | 实际生效的配置值 |
| 登录认证 | [`UserAuthService.java`](../../../server/src/main/java/com/superprogrammer/user/service/UserAuthService.java) | 桌面端 `clientLogin` / 管理后台 `adminLogin` |

---

## 六、桌面端前端：Vue 组件

| 功能 | 组件文件 | 说明 |
|---|---|---|
| 工作汇报总入口/Tab 切换 | [`WorkReportManagement.vue`](../../../src/components/work-report/WorkReportManagement.vue) | 桌面端工作汇报主界面 |
| 工作记录编辑 | [`WorkLogEditor.vue`](../../../src/components/work-report/WorkLogEditor.vue) | 新增/编辑工作记录 |
| 每日安排面板 | [`DailyPlanPanel.vue`](../../../src/components/work-report/DailyPlanPanel.vue) | 展示/完成每日计划 |
| 报告预览 | [`ReportViewer.vue`](../../../src/components/work-report/ReportViewer.vue) | 查看生成的报告内容 |
| 历史报告列表 | [`ReportHistoryList.vue`](../../../src/components/work-report/ReportHistoryList.vue) | 已生成报告的管理 |
| 报告规则配置 | [`ReportConfigForm.vue`](../../../src/components/work-report/ReportConfigForm.vue) | 配置日报/周报生成规则 |
| 推送目标配置 | [`PushTargetForm.vue`](../../../src/components/work-report/PushTargetForm.vue) | 配置飞书/钉钉推送目标 |
| 推送配置说明 | [`PushTargetGuide.vue`](../../../src/components/work-report/PushTargetGuide.vue) | 内嵌的飞书/钉钉配置傻瓜指南 |

---

## 七、桌面端前端：State/API/类型

| 功能 | 文件 | 说明 |
|---|---|---|
| 业务状态管理 | [`workReportStore.ts`](../../../src/stores/workReportStore.ts) | 工作记录、计划、配置、报告、推送等状态 |
| HTTP API 封装 | [`workReport.ts`](../../../src/api/workReport.ts) | 对后端客户端接口的 fetch 封装 |
| Rust 命令封装 | [`rustWorkReport.ts`](../../../src/api/rustWorkReport.ts) | Git 日志读取、本地通知、Markdown 导出 |
| TypeScript 类型 | [`src/types/workReport.ts`](../../../src/types/workReport.ts) | 工作汇报相关类型定义 |
| 认证状态 | [`authStore.ts`](../../../src/stores/authStore.ts) | accessToken / refreshToken / 登录态 |
| 商业授权状态 | [`commercialAuthStore.ts`](../../../src/stores/commercialAuthStore.ts) | 设备身份、模块授权、离线 token |

---

## 八、Rust 原生能力

| 功能 | 文件 | 说明 |
|---|---|---|
| Git 日志读取 | [`src-tauri/src/commands/work_report.rs`](../../../src-tauri/src/commands/work_report.rs) | 读取本地 Git 仓库提交记录 |
| 本地通知 | [`src-tauri/src/commands/work_report.rs`](../../../src-tauri/src/commands/work_report.rs) | 报告生成后的系统通知 |
| Markdown 导出 | [`src-tauri/src/commands/work_report.rs`](../../../src-tauri/src/commands/work_report.rs) | 导出报告为本地 Markdown 文件 |
| 离线授权校验 | [`src-tauri/src/commands/auth.rs`](../../../src-tauri/src/commands/auth.rs) | 离线模式下校验模块使用权限 |

---

## 九、管理后台前端

| 功能 | 文件 | 说明 |
|---|---|---|
| 用户列表/审批/禁用 | [`admin-web/src/views/UserListView.vue`](../../../admin-web/src/views/UserListView.vue) | 给用户开通 `work-report` 授权前的人口 |
| 用户详情 + 权益授予 | [`admin-web/src/views/UserDetailView.vue`](../../../admin-web/src/views/UserDetailView.vue) | 授予/撤销 `work-report` 模块权益 |
| 系统设置 | [`admin-web/src/views/SettingsView.vue`](../../../admin-web/src/views/SettingsView.vue) | 配置默认设备上限、离线缓存等 |
| 匿名设备管理 | [`admin-web/src/views/AnonymousDevicesView.vue`](../../../admin-web/src/views/AnonymousDevicesView.vue) | 匿名设备试用重置/禁用 |

---

## 十、接口调试与文档

| 用途 | 文件/工具 | 说明 |
|---|---|---|
| 后台接口请求文档 | [`../../项目相关文档/0_请求相关.md`](../../项目相关文档/0_请求相关.md) | Postman / 自建 HTTP 工具可用的接口清单 |
| 自建 HTTP 调试工具 | [`../../../http-tool/`](../../../http-tool/) | 本地 Node.js 代理工具，用于调试后台接口 |
| 模块设计说明 | [`工作汇报模块设计说明书.md`](工作汇报模块设计说明书.md) | 模块架构、表设计、接口设计 |
| 开发进度规划 | [`开发总进度规划.md`](开发总进度规划.md) | 模块整体进度、里程碑、验收标准 |
| 推送应用配置指南 | [`推送应用配置指南.md`](推送应用配置指南.md) | 飞书/钉钉傻瓜式配置文档（含对话、文件、已读） |

---

*最后更新：2026-06-24*
