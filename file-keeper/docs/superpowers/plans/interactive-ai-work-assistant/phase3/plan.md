# Phase 3: AI 报告增强

> **For agentic workers:** 本文件是 Phase 3 的路线图。开始实现前，建议用 `writing-plans` skill 将其展开为包含完整代码与命令的实现计划。

**Goal:** 让报告模板支持固定工作完成率、逾期日志、IM 录入工作记录、灵感摘要等上下文变量，AI 总结生成更立体的日报/周报。

**Architecture:** 扩展 `ReportTemplateEngine` 与 `AiSummaryService`，新增 `FixedWorkCompletionService` 负责按周期统计完成率与逾期，复用 Phase 2 的 `InspirationNoteRepository` 提供灵感摘要。

**Tech Stack:** Spring Boot, PostgreSQL, Flyway, Vue 3 + Pinia, OpenAI-compatible LLM。

---

## 目标

1. 报告模板支持固定工作完成率、逾期日志、IM 录入工作记录、灵感摘要等变量。
2. AI 总结提示词基于新上下文生成“已完成/未完成/风险/下周重点/灵感速览”。
3. 周报能展示固定任务连续未完成天数。

## 新增/修改文件

| 文件 | 操作 | 说明 |
|------|------|------|
| `server/src/main/java/.../service/FixedWorkCompletionService.java` | 创建 | 按周期统计完成率、逾期 |
| `server/src/main/java/.../dto/FixedWorkCompletionStats.java` | 创建 | 统计 DTO |
| `server/src/main/java/.../service/ReportTemplateEngine.java` | 修改 | 新增上下文变量 |
| `server/src/main/java/.../service/AiSummaryService.java` | 修改 | 使用增强提示词 |
| `server/src/main/java/.../service/WorkReportService.java` | 修改 | 传入更多上下文 |
| `server/src/main/java/.../repository/InboundMessageRepository.java` | 修改 | 查询周期内 CONFIRMED 的 IM 工作记录 |
| `server/src/main/java/.../repository/InspirationNoteRepository.java` | 修改 | 按周期/标签查询摘要 |
| `server/src/main/resources/db/migration/V14__update_default_templates.sql` | 创建 | 升级默认模板变量 |
| `src/types/workReport.ts` | 修改 | 报告类型扩展 |
| `src/components/work-report/ReportViewer.vue` | 修改 | 展示完成率等元数据 |

## 关键任务

1. **固定工作统计服务**
   - 创建 `FixedWorkCompletionService`：
     - `calculateCompletionRate(userId, startDate, endDate)`：按 item 统计完成率。
     - `findMissLog(userId, startDate, endDate)`：返回逾期未完成的 (date, itemContent) 列表。
     - `findConsecutiveMissDays(userId, itemId, endDate)`：连续未完成天数。

2. **报告上下文扩展**
   - 在 `ReportTemplateEngine.buildContext` 中新增：
     - `fixed_work_completion_rate`
     - `fixed_work_miss_log`
     - `inbox_work_logs`（周期内由 IM 录入且已确认的工作记录）
     - `inspiration_digest`（周期内/指定标签灵感摘要）
   - 保持向后兼容：若模板不含新变量，不影响旧报告。

3. **AI 提示词增强**
   - 修改 `AiSummaryService.buildPrompt`：
     - 输入增加完成率、逾期日志、IM 工作记录、灵感摘要。
     - 输出结构按 10.2 模板：本周已完成、本周未完成/逾期、下周计划、灵感速览。
   - 当 `fixedWorkItems` 为空时仍可使用其他上下文生成总结。

4. **默认模板升级**
   - 编写 Flyway 迁移 `V14__update_default_templates.sql`：
     - 将现有默认模板中的 `{{fixed_work}}` 替换为更丰富的变量组合。
     - 仅更新 `is_default=true` 且内容未自定义的模板。

5. **前端展示**
   - `ReportViewer.vue` 在报告正文下方展示“固定工作完成率”小卡片。
   - 可选：报告配置表单增加“是否包含灵感摘要”开关。

## 执行交接

Phase 3 启动前，建议将本路线图展开为独立实现计划。推荐使用 `superpowers:subagent-driven-development` 逐任务实现。
