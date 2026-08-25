-- ============================================================
-- V158：组账本类型 CHECK 扩容（子计划 A1 补丁）
--   V157 落地时 IT 暴露：project_group_ledger.ck_pgl_type 未含 MEMBER_* 三类，
--   A 计划的成员配额流水（MEMBER_ALLOCATE/RECLAIM/QUOTA_ADJUST）插入被 CHECK 拒。
--   三类为「非资金腿」（不动组池余额，对账等式白名单排除），设计见
--   specs/人工测试遗留问题修复设计.md §8.3.2。
-- ============================================================

ALTER TABLE project_group_ledger DROP CONSTRAINT ck_pgl_type;
ALTER TABLE project_group_ledger ADD CONSTRAINT ck_pgl_type
    CHECK (type IN ('ALLOCATE','RECLAIM','CONSUME','REFUND','ADMIN_ADJUST','BACKSTOP',
                    'MEMBER_ALLOCATE','MEMBER_RECLAIM','MEMBER_QUOTA_ADJUST'));
COMMENT ON COLUMN project_group_ledger.type IS
    'ALLOCATE划入/RECLAIM回收/CONSUME消耗/REFUND退款/ADMIN_ADJUST重置留痕/BACKSTOP组长兜底/MEMBER_ALLOCATE成员配额授予(毛额,非资金腿)/MEMBER_RECLAIM成员配额收回(净额口径)/MEMBER_QUOTA_ADJUST限额↔不限边界(delta=0)';

-- ============================================================
-- 回滚（rollback）：
-- ALTER TABLE project_group_ledger DROP CONSTRAINT ck_pgl_type;
-- ALTER TABLE project_group_ledger ADD CONSTRAINT ck_pgl_type
--     CHECK (type IN ('ALLOCATE','RECLAIM','CONSUME','REFUND','ADMIN_ADJUST','BACKSTOP'));  -- 已有 MEMBER_* 流水须先处理
-- ============================================================
