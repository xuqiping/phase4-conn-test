-- ============================================================
-- V77: 日志系统 · 操作审计表 audit_logs（LOG-FR-09）
-- 功能：敏感写操作/登录认证的「谁/何时/对什么/做了什么/结果如何」留痕，前端日志中心可查。
-- 设计要点：
--   1. append-only：只 INSERT/SELECT。UPDATE/DELETE 由 V78 REVOKE 在 DB 层禁止（LOG-FR-13）。
--   2. 不落 PII 原文：detail_json 只记字段名+脱敏前后值（如 {"roleId":3,"perms":["a","b"]}），
--      密码/token/用户输入原文禁止入列（安全检查清单）。
--   3. trace_id 串联：与 app.log 的 traceId 同值，一次操作日志+审计互查。
--   4. 不继承 BaseEntity：无软删/乐观锁/updated_*（只增不改，这些列无意义），id 用 IDENTITY。
-- 覆盖安全体系 S1 Step3 的「最小 audit_logs」需求（user_id/client_ip/action/detail_json 全含），
-- 故安全 S1 不再另建最小表，直接复用本表（见安全体系_S1 plan 备注修订）。
-- 回滚：DROP TABLE audit_logs;
-- ============================================================

CREATE TABLE audit_logs (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at      TIMESTAMPTZ              NOT NULL DEFAULT NOW(),      -- 发生时间（DB 时钟）
    trace_id        VARCHAR(64),                                          -- 与 app.log traceId 同值，日志↔审计互查
    user_id         BIGINT,                                               -- 操作人（未登录/系统任务为 NULL）
    username        VARCHAR(64),                                          -- 操作人登录名（冗余，防改用户名后失联）
    module          VARCHAR(32)             NOT NULL,                     -- 业务模块：auth/user/role/agent/kb/system/billing...
    action          VARCHAR(64)             NOT NULL,                     -- 动作：login/login_failed/role_update/kb_delete...
    target_type     VARCHAR(32),                                          -- 对象类型：role/agent/kb/document...
    target_id       VARCHAR(64),                                          -- 对象 id（字符串兼容非数字主键）
    detail_json     JSONB,                                                -- 摘要（字段名+脱敏值），严禁 PII 原文
    client_ip       VARCHAR(64),                                          -- X-Forwarded-For 首段或 remoteAddr
    user_agent      VARCHAR(256),                                         -- 截断存储
    result          VARCHAR(16)             NOT NULL DEFAULT 'SUCCESS'    -- SUCCESS / FAIL
);

-- 日志中心筛选三索引：按人 / 按模块 / 按时间（分页排序主用 created_at DESC）
CREATE INDEX idx_audit_user    ON audit_logs(user_id, created_at);
CREATE INDEX idx_audit_module  ON audit_logs(module, created_at);
CREATE INDEX idx_audit_created ON audit_logs(created_at);

COMMENT ON TABLE  audit_logs              IS '操作审计（append-only）：敏感写操作+登录认证留痕，只增不改（V78 REVOKE 收紧）';
COMMENT ON COLUMN audit_logs.trace_id     IS '与 app.log 的 traceId 同值，一次操作的日志与审计互查';
COMMENT ON COLUMN audit_logs.detail_json  IS '操作摘要（字段名+脱敏前后值），严禁密码/token/用户输入原文';
COMMENT ON COLUMN audit_logs.result       IS 'SUCCESS / FAIL（登录失败等也留痕）';
