-- =============================================================================
-- V8__ensure_work_plan_enhanced_columns.sql
-- 用途：幂等地确保 work_plans 增强字段存在
-- 说明：用于兼容 V7 未成功执行的场景，IF NOT EXISTS 保证重复执行不报错
-- =============================================================================

-- 计划的补充说明/备注（幂等添加）
ALTER TABLE work_plans ADD COLUMN IF NOT EXISTS description TEXT;

-- 计划预计开始时间（幂等添加）
ALTER TABLE work_plans ADD COLUMN IF NOT EXISTS planned_start_time TIME;

-- 计划预计结束时间（幂等添加）
ALTER TABLE work_plans ADD COLUMN IF NOT EXISTS planned_end_time TIME;
