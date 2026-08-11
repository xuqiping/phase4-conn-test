-- V95: llm_usage_logs 关联键（8x Chunk7 审计↔调用明细 drill-down 基建）
-- 功能：给 llm_usage_logs 加 trace_id（chat 路径关联键）+ task_id（媒体路径关联键），
--       让 admin 从审计行一键反查「这次操作调了哪些模型/多少 token/多少积分」的完整明细。
-- 设计要点：
--   1. trace_id：与 audit_logs.trace_id 同值（MDC.get("traceId")）。
--      chat 同请求「send_message → chat_completed → llm_usage_logs」三处 traceId 一致 → join。
--      media worker 是 DB 轮询异步线程，无 MDC traceId → NULL（媒体改用 task_id 关联，坑点 #10：
--      强行用 traceId 关联媒体会断链）。
--   2. task_id：媒体生成任务 id（MediaBillingService.charargeMedia 的 refId）。
--      媒体审计两行（submit + success）targetId=taskId → 与 usage 行 task_id 对齐 → join。
--      chat/embed 无任务 → NULL。
--   3. 两列均 nullable，存量行皆 NULL（旧数据无关联键，drill-down 按钮对旧行禁用/提示，#9 边界③）。
--   4. 部分索引（WHERE 列 IS NOT NULL）：跳过海量 NULL 行，索引更小更快；
--      查询「WHERE trace_id = ?」语义不变（NULL 永不匹配等值，PostgreSQL 知 NULL 行不在索引内）。
--      偏离 plan 原文「全索引」，理由：nullable 关联键列部分索引严格更优。
-- ============================================================
ALTER TABLE llm_usage_logs ADD COLUMN trace_id VARCHAR(64);
ALTER TABLE llm_usage_logs ADD COLUMN task_id  BIGINT;

CREATE INDEX idx_usage_trace ON llm_usage_logs(trace_id) WHERE trace_id IS NOT NULL;
CREATE INDEX idx_usage_task  ON llm_usage_logs(task_id)  WHERE task_id  IS NOT NULL;

COMMENT ON COLUMN llm_usage_logs.trace_id IS '请求 traceId（与 audit_logs.trace_id 同值，MDC.get("traceId")）；chat 路径关联键。media worker 无 MDC → NULL';
COMMENT ON COLUMN llm_usage_logs.task_id  IS '媒体任务 id（media 审计行 targetId 对齐，chargeMedia 的 refId）；媒体路径关联键。chat/embed 无任务 → NULL';

-- ============================================================
-- 回滚（rollback）：
-- DROP INDEX IF EXISTS idx_usage_task;
-- DROP INDEX IF EXISTS idx_usage_trace;
-- ALTER TABLE llm_usage_logs DROP COLUMN IF EXISTS task_id;
-- ALTER TABLE llm_usage_logs DROP COLUMN IF EXISTS trace_id;
-- ============================================================
