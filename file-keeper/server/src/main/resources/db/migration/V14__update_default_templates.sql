-- =============================================================================
-- V14__update_default_templates.sql
-- 用途：Phase 3 AI 报告增强
-- 1. 报告配置增加 include_inspiration_digest 开关
-- 2. 已生成报告增加 completion_rate 与 consecutive_miss_days 元数据
-- 3. 升级系统默认报告模板，支持新上下文变量
-- =============================================================================

-- -----------------------------------------------------------------------------
-- 1. 报告配置：是否在新报告中包含灵感摘要
-- -----------------------------------------------------------------------------
ALTER TABLE report_configs ADD COLUMN IF NOT EXISTS include_inspiration_digest BOOLEAN NOT NULL DEFAULT TRUE;

-- -----------------------------------------------------------------------------
-- 2. 已生成报告：固定工作完成率与连续未完成天数
-- -----------------------------------------------------------------------------
ALTER TABLE work_reports ADD COLUMN IF NOT EXISTS completion_rate DECIMAL(5,4);
ALTER TABLE work_reports ADD COLUMN IF NOT EXISTS consecutive_miss_days INT;

-- -----------------------------------------------------------------------------
-- 3. 升级默认模板：在 {{fixed_work}} 后追加新变量块
-- -----------------------------------------------------------------------------
UPDATE report_templates
SET content = REPLACE(content, '{{fixed_work}}', '{{fixed_work}}

## 固定工作完成率
{{fixed_work_completion_rate}}

## 逾期记录
{{fixed_work_miss_log}}

## 连续未完成天数
{{fixed_work_consecutive_miss_days}}

## IM 录入工作
{{inbox_work_logs}}

## 灵感速览
{{inspiration_digest}}')
WHERE is_default = TRUE AND content LIKE '%{{fixed_work}}%';
