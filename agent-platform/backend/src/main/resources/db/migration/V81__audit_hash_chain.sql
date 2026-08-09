-- ============================================================
-- V81: 安全体系 S2 · D1 审计哈希链（SEC-FR-040）+ D2/D5 钱表权限核查补齐（SEC-FR-041/044）
-- 功能：
--   1. audit_logs 加 prev_hash/record_hash 两列 —— 应用层 AuditHashChainService 在
--      insert 咽喉点按 HMAC-SHA256(canonical(row)+prev_hash, AUDIT_HMAC_KEY) 链式写入，
--      任何单行篡改/删除全链可证伪（D3 校验服务逐行重算）。
--   2. 存量行【不回填】：HMAC 密钥仅环境变量持有（迁移 SQL 算不了），且启动任务回填需
--      UPDATE 与 V78 REVOKE 冲突。链从首条新行起算（prev_hash='GENESIS'），存量行
--      由 V78 REVOKE UPDATE/DELETE 物理防篡改兜底；校验时 NULL 行只许构成连续前缀。
--   3. D2/D5 钱表核查结论与补齐：
--      - audit_logs      ：V78 已 REVOKE UPDATE/DELETE/TRUNCATE ✓
--      - points_ledger   ：V80 已 REVOKE UPDATE/DELETE/TRUNCATE ✓
--      - user_points_balance：余额行必须 UPDATE（adjustBalanceReturn），不动
--      - idempotency_keys   ：占位后须 updateResultRef（UPDATE 保留），本迁移 REVOKE DELETE/TRUNCATE
--      - payment_orders     ：当前应用只 INSERT；UPDATE 保留给 Phase2 支付回调状态机，
--                             本迁移 REVOKE DELETE/TRUNCATE（订单永不可删）
-- 回滚：
--   ALTER TABLE audit_logs DROP COLUMN prev_hash, DROP COLUMN record_hash;
--   GRANT DELETE ON payment_orders, idempotency_keys TO agent_app;
-- ============================================================

ALTER TABLE audit_logs ADD COLUMN prev_hash VARCHAR(64);
ALTER TABLE audit_logs ADD COLUMN record_hash VARCHAR(64);

COMMENT ON COLUMN audit_logs.prev_hash IS '前一行 record_hash；首条链上行=GENESIS；NULL=存量链外行（V81 前写入，由 V78 REVOKE 兜底）';
COMMENT ON COLUMN audit_logs.record_hash IS 'HMAC-SHA256(canonical(row)+prev_hash, AUDIT_HMAC_KEY) 小写 hex；NULL=存量链外行';

DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_app') THEN
        EXECUTE 'REVOKE DELETE, TRUNCATE ON payment_orders FROM agent_app';
        EXECUTE 'REVOKE DELETE, TRUNCATE ON idempotency_keys FROM agent_app';
        RAISE NOTICE 'payment_orders/idempotency_keys REVOKE DELETE,TRUNCATE applied to role agent_app';
    ELSE
        RAISE NOTICE 'role agent_app 不存在，跳过 REVOKE（本地 dev 超管账号下本就无效；生产部署须先建 agent_app 非超管账号）';
    END IF;
END $$;
