# Phase 4: 体验优化

> **For agentic workers:** 本文件是 Phase 4 的路线图。开始实现前，建议用 `writing-plans` skill 将其展开为包含完整代码与命令的实现计划。

**Goal:** 通过每日灵感回顾推送、机器人 `/help` 指令、移动端轻量化与企业微信自建应用适配，让 AI 工作助手在 IM 中更自然可用。

**Architecture:** 复用现有调度器与推送服务，新增 `InspirationReviewService`；扩展 `NlpIntentService` 与 `InboundMessageService` 处理 `help` 意图；新增 `WeComWebhookAdapter` 支持企业微信回调。

**Tech Stack:** Spring Boot, PostgreSQL, Flyway, Vue 3 + Pinia, Tauri, WeCom Open API。

---

## 目标

1. 每日灵感回顾定时推送到 IM。
2. 机器人 `/help` 指令菜单。
3. 移动端轻量化：仅通过 IM 交互即可标记完成、记录工作、添加灵感。
4. 企业微信深度适配（若需要）：接入自建应用回调。

## 新增/修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `server/src/main/java/.../service/InspirationReviewService.java` | 创建 | 每日灵感回顾调度 |
| `server/src/main/java/.../service/ReminderScheduleService.java` | 修改 | 复用调度器推送灵感回顾 |
| `server/src/main/java/.../service/NlpIntentService.java` | 修改 | 识别 `/help` 指令 |
| `server/src/main/java/.../service/InboundMessageService.java` | 修改 | 处理 help 意图并回复菜单 |
| `server/src/main/java/.../service/webhook/WeComWebhookAdapter.java` | 创建 | 企业微信自建应用适配器 |
| `server/src/main/java/.../controller/WeComWebhookController.java` | 创建 | 企业微信回调入口 |
| `server/src/main/resources/db/migration/V15__add_inspiration_review_config.sql` | 创建 | 报告配置增加灵感回顾开关 |
| `src/components/work-report/ReportConfigForm.vue` | 修改 | 增加灵感回顾配置 |

## 关键任务

1. **每日灵感回顾**
   - `InspirationReviewService`：每天固定时间查询用户 `reviewed_at` 较早的 3-5 条灵感。
   - 使用 `ReportPushService` 或 `PushService` 推送到用户绑定的 IM 目标。
   - 更新被推送的灵感 `reviewed_at`。

2. **快捷指令菜单**
   - 规则 NLP 识别以 `/` 开头或包含“帮助/help/指令/怎么用”的消息，返回 `help` 意图。
   - `InboundMessageService` 对 `help` 意图调用 pusher 回复菜单文本（不写入业务库）。
   - 菜单内容：
     ```
     可用指令：
     - 完成 [任务名]
     - 今天做了 [工作内容]
     - 灵感：[内容] #标签
     - /help
     ```

3. **移动端轻量化**
   - 确保所有高频操作（完成固定工作、记录工作、添加灵感）在 IM 中闭环完成。
   - 桌面端 Inbox 仅用于低置信度确认与历史查看。
   - 在 `InboundMessageService` 中提高 auto-confirm 阈值或优化匹配，减少用户必须打开桌面的场景。

4. **企业微信深度适配（可选）**
   - 实现 `WeComWebhookAdapter`：解析企业微信自建应用的消息 XML/JSON，验签使用 `msg_signature`。
   - 新增 `WeComWebhookController`。
   - 在文档中说明企业微信普通群机器人不支持回调，必须使用自建应用。

## 跨阶段注意事项

1. **向后兼容**：`fixed_work_completions` 从布尔 `completed` 到状态枚举的升级（PENDING/COMPLETED/SKIPPED/MISSED）若在本路线图之后进行，需要单独的迁移与数据回填计划。
2. **安全**：所有 webhook 必须验签；`inbound_messages` 按 user_id 严格隔离。
3. **测试**：每个阶段新增的服务都需要单元测试；webhook 适配器使用样例 payload 测试；前端 Store 增加对应测试。
4. **部署**：每完成一个 Phase 单独发版，避免一次性改动过大。

## 执行交接

Phase 4 启动前，建议将本路线图展开为独立实现计划。推荐使用 `superpowers:subagent-driven-development` 逐任务实现。
