-- =============================================================================
-- V5__extend_module_code_for_work_report.sql
-- 用途：扩展模块授权表的可选模块编码，新增工作汇报模块
-- 说明：新增 'work-report' 模块码，使后续工作汇报功能可被单独授权/售卖
-- =============================================================================

-- 先移除旧的 CHECK 约束（仅允许 files / processes / clipboard）
ALTER TABLE user_module_entitlements
    DROP CONSTRAINT IF EXISTS ck_user_module_entitlements_module;

-- 重新添加 CHECK 约束，加入 'work-report' 模块
ALTER TABLE user_module_entitlements
    ADD CONSTRAINT ck_user_module_entitlements_module
        CHECK (module_code IN ('files', 'processes', 'clipboard', 'work-report'));
