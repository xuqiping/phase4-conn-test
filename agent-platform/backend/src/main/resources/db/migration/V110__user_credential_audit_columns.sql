-- agent-platform/backend/src/main/resources/db/migration/V110__user_credential_audit_columns.sql
-- =============================================================================
-- V110 · user_credential 补审计列（认证系统增强 Phase 4 修复）
--
-- 问题：UserCredential 继承 BaseEntity（含 created_by/updated_by，
--       MyBatis-Plus 生成 SELECT/INSERT 列清单必含这两列），但 V102 建表漏建，
--       首次真实 DB 访问即 PSQLException「字段 "created_by" 不存在」
--       （单测 mock mapper 未暴露，Phase 4 活体验证抓出）。
-- 修复：补两列，可空（存量行无操作人；新行由 MetaObjectHandler 自动填充）。
-- =============================================================================

ALTER TABLE user_credential ADD COLUMN IF NOT EXISTS created_by BIGINT;
ALTER TABLE user_credential ADD COLUMN IF NOT EXISTS updated_by BIGINT;

COMMENT ON COLUMN user_credential.created_by IS '创建人 users.id（MetaObjectHandler 自动填充）';
COMMENT ON COLUMN user_credential.updated_by IS '最后修改人 users.id（MetaObjectHandler 自动填充）';
