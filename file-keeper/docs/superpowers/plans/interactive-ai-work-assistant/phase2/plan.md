# Phase 2: 自然语言理解与灵感随记

> **For agentic workers:** 本文件是 Phase 2 的路线图。开始实现前，建议用 `writing-plans` skill 将其展开为包含完整代码与命令的实现计划。

**Goal:** 在 Phase 1 MVP 基础上，补齐 LLM 意图识别兜底、相对日期解析、灵感随记模块、钉钉/Slack webhook 接入与 IM 确认回复。

**Architecture:** 复用 Phase 1 的 `inbound_messages` + `InboundMessageService` + 平台适配器层；新增 `inspiration_notes` 表与 `InspirationNoteService`；将 `NlpIntentService` 升级为“规则兜底 + LLM 复杂句解析”。

**Tech Stack:** Spring Boot, PostgreSQL, Flyway, Vue 3 + Pinia, Tauri, DingTalk/Slack Open API, OpenAI-compatible LLM。

---

## 目标

1. LLM 处理复杂句，规则兜底高频指令。
2. 支持相对日期解析（昨天/周一/6 月 25 日）。
3. 新增灵感随记模块：后端实体/API + 桌面端面板 + 标签管理。
4. 接入钉钉、Slack webhook（复用 Phase 1 适配器接口）。
5. IM 操作成功后回复确认消息。

## 新增/修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `server/src/main/resources/db/migration/V13__add_inspiration_notes.sql` | 创建 | 灵感随记表 |
| `server/src/main/java/.../entity/InspirationNote.java` | 创建 | 灵感实体 |
| `server/src/main/java/.../dto/InspirationNoteDto.java` | 创建 | 灵感 DTO |
| `server/src/main/java/.../dto/CreateInspirationNoteRequest.java` | 创建 | 创建请求 |
| `server/src/main/java/.../repository/InspirationNoteRepository.java` | 创建 | JdbcTemplate CRUD |
| `server/src/main/java/.../service/InspirationNoteService.java` | 创建 | 业务服务 |
| `server/src/main/java/.../controller/InspirationNoteController.java` | 创建 | REST API |
| `server/src/main/java/.../service/NlpIntentService.java` | 修改 | 接入 LLM 兜底 |
| `server/src/main/java/.../service/DateParseService.java` | 创建 | 相对日期解析 |
| `server/src/main/java/.../service/webhook/DingTalkWebhookAdapter.java` | 创建 | 钉钉适配器 |
| `server/src/main/java/.../service/webhook/SlackWebhookAdapter.java` | 创建 | Slack 适配器 |
| `server/src/main/java/.../controller/DingTalkWebhookController.java` | 创建 | 钉钉 webhook 入口 |
| `server/src/main/java/.../controller/SlackWebhookController.java` | 创建 | Slack webhook 入口 |
| `server/src/main/java/.../service/push/FeishuPusher.java` | 修改 | 支持回复单条消息（用于确认） |
| `server/src/main/java/.../service/InboundMessageService.java` | 修改 | 调用 IM 确认回复 |
| `server/src/main/java/.../service/FixedWorkService.java` | 修改 | 支持按日期查询/标记完成 |
| `server/src/main/java/.../service/WorkLogService.java` | 修改 | 支持指定日期创建 |
| `src/types/inspiration.ts` | 创建 | 前端类型 |
| `src/api/inspiration.ts` | 创建 | 前端 API |
| `src/components/work-report/InspirationPanel.vue` | 创建 | 灵感面板 |
| `src/components/work-report/InspirationQuickInput.vue` | 创建 | 快捷录入 |
| `src/components/work-report/FixedWorkCompletionCalendar.vue` | 创建 | 固定工作完成日历 |
| `src/stores/workReportStore.ts` | 修改 | 新增灵感状态与动作 |
| `src/components/work-report/WorkReportManagement.vue` | 修改 | 新增灵感 Tab |

## 关键任务

1. **数据库迁移 V13**
   - 创建 `inspiration_notes` 表（字段：id, user_id, content, tags text[], source, platform_message_id, report_config_ids bigint[], reviewed_at, created_at/updated_at, deleted）。

2. **LLM 意图识别升级**
   - 在 `NlpIntentService` 中保留规则快速路径。
   - 当规则返回 `unknown` 或置信度 < 0.6 时，调用 `AiSummaryService` 或新增的 `LlmIntentClient`，使用 10.1 提示词模板识别意图。
   - 对 LLM 返回 JSON 做 schema 校验，失败则保持 `unknown`。

3. **相对日期解析**
   - 创建 `DateParseService`：支持“今天、昨天、周一、上周X、6 月 25 日、2026-06-25”。
   - 在 NLP 结果中将 `date` 统一输出为 `YYYY-MM-DD`。
   - 规则 NLP 默认 `date=today`，LLM 负责复杂日期。

4. **灵感随记后端**
   - 实现 `InspirationNoteService`：创建、按标签/时间范围查询、软删除、更新 `reviewed_at`。
   - 在 `InboundMessageService.executeIntent` 中增加 `add_inspiration` 分支，自动创建灵感笔记。

5. **多平台 Webhook**
   - 实现 `DingTalkWebhookAdapter`、`SlackWebhookAdapter`。
   - 新增对应 Controller，复用 `InboundMessageService.receive(platform, parseResult)`。
   - 钉钉使用 `sign` + `timestamp` 验签；Slack 使用 `X-Slack-Signature` 验签。

6. **IM 确认回复**
   - 扩展 `PushService` 接口增加 `reply(String messageId, String text, PushTarget target, String credential)` 或使用现有 `push` 方法发送文本到同一目标。
   - 在 `InboundMessageService.executeIntent` 成功后，异步调用 pusher 发送确认消息（如“已记录：完成日报设计”）。

7. **前端灵感模块**
   - `InspirationPanel.vue`：列表、标签筛选、快捷输入、删除。
   - `FixedWorkCompletionCalendar.vue`：按天展示固定工作完成状态。
   - 扩展 Store 与 `WorkReportManagement.vue` 增加入口。

## 执行交接

Phase 2 启动前，建议将本路线图展开为独立实现计划。推荐使用 `superpowers:subagent-driven-development` 逐任务实现。
