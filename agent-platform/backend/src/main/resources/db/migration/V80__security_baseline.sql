-- ============================================================
-- V80: 安全体系 S1 · 止血与基线（billing 安全段）
-- 内容（对应 安全体系_S1止血与基线.plan.md Step 8/9/11）：
--   [Step 8 · SEC-FR-120] user_points_balance 加 CHECK (balance_points >= 0) 兜底，
--       配合 mapper 层 `AND balance_points + delta >= 0` SQL 守卫双保险。
--       ⚠️ 语义变更：V65 的「可负=欠款」模型自此作废——并发透支笔直接拒扣（0 行 RETURNING），
--       不再允许瞬时负余额。迁移前预检：SELECT count(*) FROM user_points_balance WHERE balance_points < 0;
--       非 0 须先人工调账再执行本迁移（2026-08-09 dev 库预检=0）。
--   [Step 9 · SEC-FR-121] idempotency_keys 幂等键去重表 + points_ledger 业务唯一约束兜底。
--   [Step 11 · SEC-FR-123] points_ledger 只增不改（REVOKE UPDATE/DELETE，沿用 V78 范式）。
-- 回滚：
--   ALTER TABLE user_points_balance DROP CONSTRAINT IF EXISTS chk_balance_non_negative;
--   DROP TABLE IF EXISTS idempotency_keys;
--   ALTER TABLE points_ledger DROP CONSTRAINT IF EXISTS uq_ledger_ref;
--   GRANT UPDATE, DELETE ON points_ledger TO <应用账号>;  -- 按部署账号名
-- ============================================================

-- ---------- Step 8 · SEC-FR-120：余额非负 CHECK 兜底 ----------
ALTER TABLE user_points_balance
    ADD CONSTRAINT chk_balance_non_negative CHECK (balance_points >= 0);

COMMENT ON COLUMN user_points_balance.balance_points IS '积分余额，非负（V80 CHECK 硬约束 + mapper SQL 守卫；S1 起欠款模型作废：透支笔拒扣不再欠债）';

-- ---------- Step 9 · SEC-FR-121：幂等键去重表 ----------
-- 用途：积分变动/关键写接口的幂等键占位（先 INSERT ... ON CONFLICT DO NOTHING 占位，
--       撞键 → 回查首次流水返回相同结果；扣减与占位同事务，失败整体回滚不留死键）。
CREATE TABLE idempotency_keys (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    idem_key        VARCHAR(128)             NOT NULL UNIQUE,             -- 幂等键（调用方生成，全局唯一）
    user_id         BIGINT                   NOT NULL,                    -- 归属用户
    scope           VARCHAR(64)              NOT NULL,                    -- 作用域：billing.charge/billing.refund/billing.grant...
    result_ref      VARCHAR(128),                                       -- 首次生效的业务引用（如 ledger id），撞键回查用
    created_at      TIMESTAMPTZ              NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE idempotency_keys IS '幂等键去重表（S1 SEC-FR-121）：同键重复提交只生效一次，撞键回查首次结果';

-- ---------- Step 9 · SEC-FR-121：流水业务唯一约束兜底 ----------
-- 同一业务引用同类型只记一笔（防占位表被绕过时的最后防线）。
-- 注意：ref_id 可空（系统扣减无引用），PG 中 NULL 不参与唯一冲突——正是所需语义。
ALTER TABLE points_ledger
    ADD CONSTRAINT uq_ledger_ref UNIQUE (ref_type, ref_id, type);

-- ---------- Step 11 · SEC-FR-123：流水只增不改 ----------
-- 应用账号禁 UPDATE/DELETE points_ledger（对账可信源；沿用 V78 audit_logs 同范式）。
-- CURRENT_USER = 迁移执行账号 = 应用运行账号（本部署同一账号）。
REVOKE UPDATE, DELETE ON points_ledger FROM CURRENT_USER;
