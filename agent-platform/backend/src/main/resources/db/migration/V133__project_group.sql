-- ============================================================
-- V133: 项目组与积分划拨 · 数据层（7x#3/#4，spec §3）
-- 功能：项目组实体 + 组池/成员记账/组流水三账表 + 存量表 gid 加列 + 个人流水枚举扩展
-- 设计要点：
--   1. 双钱包镜像：project_group_wallets 单行/组（镜像 user_points_balance 行锁模式），
--      但 CHECK >= 0 —— 组池不可透支（个人可负=欠款语义，组池差额走 BACKSTOP 扣组长）。
--   2. 成员记账：used_points 为同事务维护的冗余快照（避免 SUM(usage_log) 扫描）；
--      quota_limit_points NULL=不限（组长默认 NULL）；UNIQUE(group_id,user_id) 防重复入组。
--   3. project_group_ledger append-only（镜像 points_ledger 先例：不可变、无软删/乐观锁），
--      每笔落 balance_after 可正向重建对账；actor_user_id 区分谁动的钱（消耗=成员/划拨回收=组长）。
--   4. 存量表加列全部 nullable —— 老行/回滚零感知；gid 是账单与产出的唯一归属事实源
--      （媒体扣费本就落 llm_usage_logs 带 taskId）。不加 FK：组可删、历史账要留（性能+生命周期解耦）。
--   5. points_ledger type 枚举扩展须先 DROP 再 ADD CHECK（PG 不支持 ALTER CONSTRAINT 加值）；
--      ref_type 无 CHECK，GROUP 值直接可用（ref_id=groupId）。
-- 回滚：新表 drop 即回滚；两加列可 drop；已产生的组账流水回滚前必须导出（对账依据）。
-- ============================================================

-- 1. 项目组（软删：balance>0 时服务层拒删，物理删永不发生）
CREATE TABLE project_groups (
    id              BIGINT                    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by      BIGINT,
    created_at      TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    deleted         INTEGER                   NOT NULL DEFAULT 0,      -- 软删（@TableLogic）
    version         INTEGER                   NOT NULL DEFAULT 0,      -- 乐观锁行版本
    name            VARCHAR(64)               NOT NULL,
    owner_user_id   BIGINT                    NOT NULL,                -- 组长（建组自动成员行，quota NULL）
    description     VARCHAR(500)
);
CREATE INDEX idx_pgroup_owner ON project_groups(owner_user_id) WHERE deleted = 0;
COMMENT ON TABLE  project_groups            IS '项目组（7x#3）：组长建组拉成员，个人积分划入组池共用。软删前置校验组池 balance=0';
COMMENT ON COLUMN project_groups.owner_user_id IS '组长（唯一管理权：划拨/回收/成员增删/限额调整）';

-- 2. 组成员（组长建组自动写入一行；quota NULL=不限）
CREATE TABLE project_group_members (
    id                  BIGINT                GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    deleted             INTEGER               NOT NULL DEFAULT 0,
    version             INTEGER               NOT NULL DEFAULT 0,
    group_id            BIGINT                NOT NULL,
    user_id             BIGINT                NOT NULL,
    quota_limit_points  NUMERIC(14,2),                            -- NULL=不限；累计消耗上限（调低不追偿）
    used_points         NUMERIC(14,2)         NOT NULL DEFAULT 0,    -- 冗余快照：随消耗增/退款减（同事务）
    CONSTRAINT uk_pgm_group_user UNIQUE (group_id, user_id),
    CONSTRAINT ck_pgm_quota_nonneg CHECK (quota_limit_points IS NULL OR quota_limit_points >= 0),
    CONSTRAINT ck_pgm_used_nonneg  CHECK (used_points >= 0),
    CONSTRAINT fk_pgm_group FOREIGN KEY (group_id) REFERENCES project_groups(id) ON DELETE CASCADE
);
CREATE INDEX idx_pgm_user ON project_group_members(user_id) WHERE deleted = 0;
COMMENT ON TABLE  project_group_members               IS '组成员记账行：quota_limit_points=累计消耗上限（NULL 不限），used_points=已耗快照（同事务维护免 SUM 扫描）';
COMMENT ON COLUMN project_group_members.quota_limit_points IS '组长配置的成员累计限额；调低不追溯已耗仅限后续；重置走组流水 ADMIN_ADJUST delta=0 留痕';

-- 3. 组池钱包（单行/组，不可透支）
CREATE TABLE project_group_wallets (
    id              BIGINT                    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    group_id        BIGINT                    NOT NULL,
    balance_points  NUMERIC(14,2)             NOT NULL DEFAULT 0,
    updated_at      TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_pgw_group UNIQUE (group_id),
    CONSTRAINT ck_pgw_nonneg CHECK (balance_points >= 0),          -- 组池不可透支（差额走 BACKSTOP 扣组长）
    CONSTRAINT fk_pgw_group FOREIGN KEY (group_id) REFERENCES project_groups(id) ON DELETE CASCADE
);
COMMENT ON TABLE  project_group_wallets            IS '组池余额（单行/组，镜像个人钱包行锁模式）。CHECK>=0：不可透支';
COMMENT ON COLUMN project_group_wallets.balance_points IS '组池余额；结算时余额不足的差额走 BACKSTOP 从组长个人扣，保此列恒 >= 0';

-- 4. 组流水（append-only 对账；谁动的钱 actor 一眼可查）
CREATE TABLE project_group_ledger (
    id              BIGINT                    GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at      TIMESTAMPTZ               NOT NULL DEFAULT NOW(),
    group_id        BIGINT                    NOT NULL,
    actor_user_id   BIGINT                    NOT NULL,               -- 消耗=成员；划拨/回收/调整=组长
    type            VARCHAR(24)               NOT NULL,
    delta_points    NUMERIC(14,2)             NOT NULL,               -- 正=入组池（划拨/退款），负=出（消耗/回收）
    balance_after   NUMERIC(14,2)             NOT NULL,               -- 本笔后组池余额（对账基准）
    ref_type        VARCHAR(16),                                        -- CHAT/EMBED/VIDEO/IMAGE/MEDIA/GROUP/ADMIN
    ref_id          VARCHAR(64),                                        -- usage_log id / media task id / groupId
    remark          VARCHAR(255),
    CONSTRAINT ck_pgl_type CHECK (type IN ('ALLOCATE','RECLAIM','CONSUME','REFUND','ADMIN_ADJUST','BACKSTOP')),
    CONSTRAINT fk_pgl_group FOREIGN KEY (group_id) REFERENCES project_groups(id)   -- 无级联：组账历史不可随组消失
);
CREATE INDEX idx_pgl_group_time ON project_group_ledger(group_id, created_at);
CREATE INDEX idx_pgl_actor_time ON project_group_ledger(actor_user_id, created_at);
COMMENT ON TABLE  project_group_ledger       IS '组池流水（append-only，每笔落 balance_after 可正向重建）。actor_user_id：消耗=成员/划拨回收=组长';
COMMENT ON COLUMN project_group_ledger.type  IS 'ALLOCATE 划入/RECLAIM 回收/CONSUME 成员消耗/REFUND 退款/ADMIN_ADJUST 限额重置(delta=0留痕)/BACKSTOP 组池不足组长兜底差额';
COMMENT ON COLUMN project_group_ledger.balance_after IS '本笔后组池余额（对账基准：末行 balance_after = wallets.balance_points）';

-- 5. 存量表加列：llm_usage_logs / media_gen_tasks + project_group_id（账单与产出唯一归属事实源）
ALTER TABLE llm_usage_logs ADD COLUMN project_group_id BIGINT;
COMMENT ON COLUMN llm_usage_logs.project_group_id IS '项目组归属（NULL=个人）。账单/项目推进/限额三处共用的唯一事实源；无 FK（组可删历史账要留）';
CREATE INDEX idx_usage_group_time ON llm_usage_logs(project_group_id, created_at) WHERE project_group_id IS NOT NULL;

ALTER TABLE media_gen_tasks ADD COLUMN project_group_id BIGINT;
ALTER TABLE media_gen_tasks ADD COLUMN estimated_cost NUMERIC(14,2);
COMMENT ON COLUMN media_gen_tasks.project_group_id IS '项目组归属（NULL=个人）；worker 结算按此分支组池/个人扣费';
COMMENT ON COLUMN media_gen_tasks.estimated_cost IS '提交时估价快照（价表缺价记 0+WARN）；回收在途校验上限=Σ(status∈PENDING/RUNNING)的此列';
CREATE INDEX idx_mgen_group_time ON media_gen_tasks(project_group_id, created_at) WHERE project_group_id IS NOT NULL;

-- 6. 个人流水枚举扩展：划拨去向/回收来源在个人账本可追溯（ref_type=GROUP，ref_id=groupId）
ALTER TABLE points_ledger DROP CONSTRAINT chk_ledger_type;
ALTER TABLE points_ledger ADD CONSTRAINT chk_ledger_type
    CHECK (type IN ('RECHARGE','CONSUME','REFUND','ADMIN_GRANT','GROUP_ALLOCATE','GROUP_RECLAIM'));
COMMENT ON COLUMN points_ledger.type IS 'RECHARGE充值/CONSUME消耗/REFUND退款/ADMIN_GRANT管理员发放/GROUP_ALLOCATE划入组池/GROUP_RECLAIM从组池回收';
COMMENT ON COLUMN points_ledger.ref_type IS 'CHAT/EMBED/VIDEO/IMAGE/PAYMENT/ADMIN/GROUP（组划拨，ref_id=project_groups.id）';

-- ============================================================
-- 运维 SQL 模板（异常组池修正用，勿直接执行——先对账再动账）：
--   对账：末行流水 vs 钱包余额（应为空集）
--     SELECT w.group_id, w.balance_points, l.balance_after
--     FROM project_group_wallets w
--     JOIN LATERAL (SELECT balance_after FROM project_group_ledger
--                   WHERE group_id = w.group_id ORDER BY id DESC LIMIT 1) l ON TRUE
--     WHERE w.balance_points <> l.balance_after;
--   成员 used 对账：Σ(CONSUME-REFUND actor) vs used_points（应为空集）
--     SELECT m.group_id, m.user_id, m.used_points, COALESCE(t.net, 0) AS ledger_used
--     FROM project_group_members m
--     LEFT JOIN (SELECT group_id, actor_user_id,
--                       SUM(CASE WHEN type = 'CONSUME' THEN delta_points
--                                WHEN type = 'REFUND'  THEN -delta_points END) AS net
--                FROM project_group_ledger WHERE type IN ('CONSUME','REFUND')
--                GROUP BY group_id, actor_user_id) t ON t.group_id = m.group_id AND t.actor_user_id = m.user_id
--     WHERE COALESCE(t.net, 0) <> m.used_points;
-- ============================================================
-- 回滚（rollback）：
-- ALTER TABLE points_ledger DROP CONSTRAINT chk_ledger_type;
-- ALTER TABLE points_ledger ADD CONSTRAINT chk_ledger_type
--     CHECK (type IN ('RECHARGE','CONSUME','REFUND','ADMIN_GRANT'));  -- 已有 GROUP_* 流水须先处理
-- ALTER TABLE media_gen_tasks DROP COLUMN IF EXISTS estimated_cost;
-- ALTER TABLE media_gen_tasks DROP COLUMN IF EXISTS project_group_id;
-- ALTER TABLE llm_usage_logs  DROP COLUMN IF EXISTS project_group_id;
-- DROP TABLE IF EXISTS project_group_ledger;    -- ⚠ 回滚前必须导出（组账对账依据）
-- DROP TABLE IF EXISTS project_group_wallets;
-- DROP TABLE IF EXISTS project_group_members;
-- DROP TABLE IF EXISTS project_groups;
-- ============================================================
