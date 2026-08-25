-- ============================================================
-- V157：人工测试遗留问题修复（子计划 B1 / D1 前置）
--   1. users.remark（D1：注册/个人资料/管理员三处可维护的账号备注，管理列表 keyword 可筛）
--   2. user_points_balance.debt_points（B5：DEBT 兜底欠款列，Q10=A——
--      没拦住的消耗「扣到 0 + 差额挂账」，冗余列避免大流水表 SUM；
--      欠款>0 拦截一切消费入口，充值/发放自动先还）
--   3. points_ledger 枚举扩展 DEBT/DEBT_REPAY（挂账/还款流水腿）
-- 设计：specs/人工测试遗留问题修复设计.md §4.3 层4、§9
-- ============================================================

-- 1. 账号备注（D1）
ALTER TABLE users ADD COLUMN remark VARCHAR(128);
COMMENT ON COLUMN users.remark IS '账号备注（≤128 字）：注册/个人资料/管理员可维护，管理列表 keyword 命中';

-- 2. 欠款列（B5，默认 0=无欠款；>=0 兜底）
ALTER TABLE user_points_balance ADD COLUMN debt_points NUMERIC(18,2) NOT NULL DEFAULT 0;
ALTER TABLE user_points_balance DROP CONSTRAINT IF EXISTS chk_debt_non_negative;
ALTER TABLE user_points_balance
    ADD CONSTRAINT chk_debt_non_negative CHECK (debt_points >= 0);
COMMENT ON COLUMN user_points_balance.debt_points
    IS '欠款积分（DEBT 兜底）：余额扣尽后的未付差额挂此列；>0 时拦截全部消费入口，充值/发放自动优先冲抵（DEBT_REPAY 流水留痕）';

-- 3. 个人流水枚举扩展（挂账/还款两腿）
ALTER TABLE points_ledger DROP CONSTRAINT chk_ledger_type;
ALTER TABLE points_ledger ADD CONSTRAINT chk_ledger_type
    CHECK (type IN ('RECHARGE','CONSUME','REFUND','ADMIN_GRANT','GROUP_ALLOCATE','GROUP_RECLAIM','DEBT','DEBT_REPAY'));
COMMENT ON COLUMN points_ledger.type IS 'RECHARGE充值/CONSUME消耗/REFUND退款/ADMIN_GRANT管理员发放/GROUP_ALLOCATE划入组池/GROUP_RECLAIM从组池回收/DEBT余额扣尽挂账/DEBT_REPAY充值或发放冲抵欠款';

-- ============================================================
-- 回滚（rollback）：
-- ALTER TABLE points_ledger DROP CONSTRAINT chk_ledger_type;
-- ALTER TABLE points_ledger ADD CONSTRAINT chk_ledger_type
--     CHECK (type IN ('RECHARGE','CONSUME','REFUND','ADMIN_GRANT','GROUP_ALLOCATE','GROUP_RECLAIM'));  -- 已有 DEBT/DEBT_REPAY 流水须先处理
-- ALTER TABLE user_points_balance DROP CONSTRAINT IF EXISTS chk_debt_non_negative;
-- ALTER TABLE user_points_balance DROP COLUMN IF EXISTS debt_points;
-- ALTER TABLE users DROP COLUMN IF EXISTS remark;
-- ============================================================
