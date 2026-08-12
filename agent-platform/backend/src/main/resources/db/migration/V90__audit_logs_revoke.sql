-- ============================================================
-- V78: 日志系统 · audit_logs DB 层防篡改（LOG-FR-13）
-- 功能：REVOKE 应用账号对 audit_logs 的 UPDATE/DELETE —— 审计只许 INSERT/SELECT，
--       即便应用层被拖库注入也无法抹掉/改写留痕。
-- 注意：
--   1. 角色名按部署环境取（生产应用账号应为非 superuser 的 agent_app；本地 dev 用 postgres
--      超管，REVOKE 对超管无效——故用 DO 块判角色存在再执行，缺角色不炸迁移）。
--   2. 归档策略（按月分区/定时导出）留 TODO：量上来再做（推进计划 P1-5，当前单表+索引足够）。
-- 回滚：GRANT UPDATE, DELETE ON audit_logs TO agent_app;（DO 块内反向执行）
-- ============================================================

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_app') THEN
        EXECUTE 'REVOKE UPDATE, DELETE, TRUNCATE ON audit_logs FROM agent_app';
        RAISE NOTICE 'audit_logs REVOKE applied to role agent_app';
    ELSE
        RAISE NOTICE 'role agent_app 不存在，跳过 REVOKE（本地 dev 超管账号下本就无效；生产部署须先建 agent_app 非超管账号）';
    END IF;
END $$;
