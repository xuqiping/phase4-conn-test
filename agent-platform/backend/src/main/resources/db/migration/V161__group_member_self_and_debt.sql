-- V161：修复III Chunk A —— 组内成员名下余额与欠款拆分（规格 §3.1）
--
-- 生活比喻：project_group_members 原是「健身房会员卡」——quota_limit_points=教练给你的
-- 月度上限，used_points=你已刷掉多少。本版给卡加三个口袋：
--   self_points        你自己充进卡里的私房钱（教练回收组池/调你限额都碰不到它）
--   debt_pool_points   你超刷后场馆（组池）垫付的钱
--   debt_leader_points 你超刷后教练（组长个人）垫付的钱
-- 还款时各回各家：先还教练垫的（真金白银），再还场馆垫的。

ALTER TABLE project_group_members
    ADD COLUMN self_points        NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN debt_pool_points   NUMERIC(12,2) NOT NULL DEFAULT 0,
    ADD COLUMN debt_leader_points NUMERIC(12,2) NOT NULL DEFAULT 0;

ALTER TABLE project_group_members
    ADD CONSTRAINT ck_pgm_self_nonneg        CHECK (self_points >= 0),
    ADD CONSTRAINT ck_pgm_debt_pool_nonneg   CHECK (debt_pool_points >= 0),
    ADD CONSTRAINT ck_pgm_debt_leader_nonneg CHECK (debt_leader_points >= 0);

-- 组账本类型扩容（沿用 V158 的 DROP+ADD 模式）：
--   SELF_ALLOCATE 个人划拨入组（还款后余款进名下）
--   SELF_CONSUME  消耗扣名下余额（瀑布第②腿）
--   SELF_REFUND   名下退款/退组退回个人钱包
--   SELF_REPAY    还款（先组长垫后退组池垫，备注写清去向与金额）
--   DEBT_WRITEOFF 欠款核销/调限额豁免（债清但无资金流动）
ALTER TABLE project_group_ledger DROP CONSTRAINT ck_pgl_type;
ALTER TABLE project_group_ledger ADD CONSTRAINT ck_pgl_type
    CHECK (type IN ('ALLOCATE','RECLAIM','CONSUME','REFUND','ADMIN_ADJUST','BACKSTOP',
                    'MEMBER_ALLOCATE','MEMBER_RECLAIM','MEMBER_QUOTA_ADJUST',
                    'SELF_ALLOCATE','SELF_CONSUME','SELF_REFUND','SELF_REPAY','DEBT_WRITEOFF'));

-- 存量回填（一次性，幂等）：兜底时代 BACKSTOP 无条件计入 used，可能出现 used > quota。
-- 拆分口径：溢出 = used - quota；组长垫占比按组级 ΣBACKSTOP/(ΣCONSUME+ΣBACKSTOP) 近似
--（账本 BACKSTOP 行不记 consumer，只能组级比例归因），余量归组池垫；used 落到 quota。
-- 幂等条件：debt 合计已 >0 的行（新代码产物）或 used<=quota 的行不动，重跑无效果。
WITH ledger_agg AS (
    SELECT group_id,
           ABS(SUM(CASE WHEN type = 'CONSUME'  THEN delta_points ELSE 0 END)) AS consume_sum,
           ABS(SUM(CASE WHEN type = 'BACKSTOP' THEN delta_points ELSE 0 END)) AS backstop_sum
    FROM project_group_ledger
    WHERE type IN ('CONSUME', 'BACKSTOP')
    GROUP BY group_id
),
grp_share AS (
    SELECT g.id AS group_id,
           CASE WHEN COALESCE(a.consume_sum, 0) + COALESCE(a.backstop_sum, 0) = 0 THEN 0
                ELSE LEAST(COALESCE(a.backstop_sum, 0)::numeric
                           / (COALESCE(a.consume_sum, 0) + COALESCE(a.backstop_sum, 0)), 1) END
           AS leader_share
    FROM project_groups g
    LEFT JOIN ledger_agg a ON a.group_id = g.id
)
UPDATE project_group_members m
SET used_points        = m.quota_limit_points,
    debt_leader_points = ROUND((m.used_points - m.quota_limit_points) * gs.leader_share, 2),
    debt_pool_points   = (m.used_points - m.quota_limit_points)
                         - ROUND((m.used_points - m.quota_limit_points) * gs.leader_share, 2),
    updated_at         = NOW()
FROM grp_share gs
WHERE gs.group_id = m.group_id
  AND m.deleted = 0
  AND m.quota_limit_points IS NOT NULL
  AND m.used_points > m.quota_limit_points
  AND (m.debt_pool_points + m.debt_leader_points) = 0;

-- 验证口径（人工核对用，不在迁移内执行）：
--   SELECT COUNT(*) FROM project_group_members WHERE used_points > quota_limit_points;  -- 回填后应为 0
--   对账不变量：Σ(CONSUME+SELF_CONSUME+BACKSTOP-REFUND 各腿) == used + debt_pool + debt_leader
