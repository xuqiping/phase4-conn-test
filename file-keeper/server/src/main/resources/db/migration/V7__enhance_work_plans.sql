-- =============================================================================
-- V7__enhance_work_plans.sql
-- 用途：增强每日安排表，支持更丰富的计划描述和时间安排
-- 说明：为 work_plans 增加说明、计划开始/结束时间字段，便于展示日程时间段
-- =============================================================================

-- 计划的补充说明/备注
ALTER TABLE work_plans ADD COLUMN description TEXT;

-- 计划预计开始时间
ALTER TABLE work_plans ADD COLUMN planned_start_time TIME;

-- 计划预计结束时间
ALTER TABLE work_plans ADD COLUMN planned_end_time TIME;

-- 注：H2 不支持单条 ALTER TABLE 同时添加多列，因此拆分为三条独立语句
-- PostgreSQL 同样兼容上述单条 ALTER COLUMN 语法
