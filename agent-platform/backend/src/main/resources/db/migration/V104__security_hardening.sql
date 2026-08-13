-- ============================================================
-- V104: 安全运维日志告警加固 · 地基（11x，spec: docs/specs/安全运维日志告警加固.md）
-- 注：原拟 V100，记忆流 V100__memory_entry_source_turn_input_id 已先入库（2026-08-12），
--     V101/V102/V103 被 RAG/认证流占用 → 本迁移改号 V104（多工作流并行取号先查 flyway_schema_history）。
-- 内容：3 新表（security_events / ip_blacklist / login_attempts）
--       + users 扩 2 列（ban_reason / locked_until）+ status CHECK 扩 BANNED
--       + 3 新权限 seed 授 admin 角色
-- 回滚：
--   DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code LIKE 'security:%');
--   DELETE FROM permissions WHERE code IN ('security:event:read','security:ban:manage','security:rule:manage');
--   DROP TABLE IF EXISTS security_events, ip_blacklist, login_attempts;
--   ALTER TABLE users DROP CONSTRAINT users_status_check;
--   ALTER TABLE users ADD CONSTRAINT users_status_check CHECK (status IN ('ACTIVE','DISABLED','LOCKED'));
--   ALTER TABLE users DROP COLUMN ban_reason, DROP COLUMN locked_until;
-- ============================================================

-- ------------------------------------------------------------
-- 1. users 扩列：封号原因 + 临时锁定到期时间
-- ------------------------------------------------------------
ALTER TABLE users ADD COLUMN IF NOT EXISTS ban_reason   VARCHAR(128);
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_until TIMESTAMPTZ;

COMMENT ON COLUMN users.ban_reason   IS '封号/锁定原因（admin 填写或规则码）';
COMMENT ON COLUMN users.locked_until IS '临时锁定到期时间（到期自动解锁）；NULL=非临时锁';

-- 2. status CHECK 扩 BANNED（区分「安全封号」vs「普通禁用 DISABLED」）。
--    V1 内联 CHECK 未命名 → PG 自动命名 users_status_check，DROP+ADD 重建。
ALTER TABLE users DROP CONSTRAINT users_status_check;
ALTER TABLE users ADD CONSTRAINT users_status_check
    CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED', 'BANNED'));

-- ------------------------------------------------------------
-- 3. security_events：安全事件流（半年留存，运维可物理删）。
--    大白话：一行 = 一次「可疑行为命中」，只插不挂 @TableLogic，到期 DELETE 清理。
--    与 audit_logs（append-only 铁证，V91 REVOKE）完全隔离——本表可删，audit 不可删。
-- ------------------------------------------------------------
CREATE TABLE security_events (
    id           BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at   TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    event_type   VARCHAR(48)                 NOT NULL,   -- 规则码 LOGIN_BRUTE_FORCE/SQLI_PROBE/...
    severity     VARCHAR(8)                  NOT NULL,   -- LOW/MEDIUM/HIGH/CRITICAL
    user_id      BIGINT,                                  -- 涉及用户（匿名 IP 探测可空）
    client_ip    VARCHAR(64),                             -- IPv4/IPv6（归一化后存）
    trace_id     VARCHAR(64),                             -- 串 app.log 全请求日志
    rule_id      VARCHAR(32),                             -- 命中的规则配置 ID
    detail_json  JSONB,                                   -- 上下文（已脱敏，禁 PII 原文）
    auto_action  VARCHAR(24)                 NOT NULL DEFAULT 'NONE',  -- NONE/IP_BLOCKED/ACCOUNT_LOCKED/ACCOUNT_BANNED/TOKEN_REVOKED
    handled      BOOLEAN                     NOT NULL DEFAULT FALSE,   -- 运维是否已处置
    handled_by   VARCHAR(64),
    handled_at   TIMESTAMPTZ
);

COMMENT ON TABLE security_events IS '安全事件流（11x 加固）：一行=一次可疑行为命中，半年留存，运维可物理删';
COMMENT ON COLUMN security_events.event_type  IS '检测规则码（13 个检测码，见 spec 4.2）';
COMMENT ON COLUMN security_events.auto_action IS '自动响应结果码（非检测规则）';

CREATE INDEX idx_sec_event_created  ON security_events (created_at DESC);
CREATE INDEX idx_sec_event_type     ON security_events (event_type);
CREATE INDEX idx_sec_event_severity ON security_events (severity);
CREATE INDEX idx_sec_event_user     ON security_events (user_id);
CREATE INDEX idx_sec_event_ip       ON security_events (client_ip);
-- 部分索引：待办视图只查 handled=false 子集，行少索引小
CREATE INDEX idx_sec_event_handled  ON security_events (handled) WHERE handled = FALSE;

-- ------------------------------------------------------------
-- 4. ip_blacklist：IP 封禁（自动+手动合一）。
--    大白话：一个 IP 一行，banned_until 到期自动失效；NULL=永久。
--    热路径查询走 Redis 镜像（本表是 DB 兜底/持久层）。
-- ------------------------------------------------------------
CREATE TABLE ip_blacklist (
    id           BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    ip           VARCHAR(64)                 NOT NULL,   -- 归一化后的 IP
    source       VARCHAR(16)                 NOT NULL,   -- AUTO / MANUAL
    reason       VARCHAR(128),                            -- 触发规则码 / 人工填写
    banned_until TIMESTAMPTZ,                             -- 过期自动解；NULL=永久
    created_by   VARCHAR(64),                             -- 创建人（AUTO 时填规则码）
    created_at   TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_ip_blacklist_ip UNIQUE (ip)
);

COMMENT ON TABLE ip_blacklist IS 'IP 封禁表（11x 加固）：AUTO=规则自动封，MANUAL=admin 手动封';

-- ------------------------------------------------------------
-- 5. login_attempts：登录尝试原料（30 天滚动清理，取证用）。
--    大白话：每次登录成功/失败留一行；暴破=同 identifier 连续失败，撞库=同 IP 试多账号。
--    检测计数走 Redis（login:fail:* 已存，SEC-FR-001），本表只做取证与异地检测数据源。
-- ------------------------------------------------------------
CREATE TABLE login_attempts (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at  TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    identifier  VARCHAR(128)                NOT NULL,   -- 试登的用户名/邮箱
    user_id     BIGINT,                                  -- 命中用户（user_not_found 时空）
    client_ip   VARCHAR(64)                 NOT NULL,
    success     BOOLEAN                     NOT NULL,
    fail_reason VARCHAR(32),                             -- bad_password/no_such_user/locked
    geo         VARCHAR(64)                              -- ip2region 归属地（异地检测用）
);

COMMENT ON TABLE login_attempts IS '登录尝试取证表（11x 加固）：30 天滚动，检测计数走 Redis 不查本表';

CREATE INDEX idx_login_ip_time ON login_attempts (client_ip, created_at DESC);
CREATE INDEX idx_login_id_time ON login_attempts (identifier, created_at DESC);

-- ------------------------------------------------------------
-- 6. 3 新权限 seed + 授 admin 角色（对齐 V91 审计权限 seed 范式）
-- ------------------------------------------------------------
INSERT INTO permissions (name, code, resource, action) VALUES
    ('安全事件查看', 'security:event:read', 'security', 'event:read'),
    ('封号封IP管理', 'security:ban:manage', 'security', 'ban:manage'),
    ('安全规则配置', 'security:rule:manage', 'security', 'rule:manage')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin'
  AND p.code IN ('security:event:read', 'security:ban:manage', 'security:rule:manage')
ON CONFLICT DO NOTHING;
