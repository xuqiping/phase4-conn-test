-- ============================================================
-- V65: 积分计费系统 · 钱包层（user_points_balance / points_ledger / payment_order / llm_usage_logs）
-- 功能：预付积分钱包余额 + append-only 流水对账 + 充值订单 + LLM 调用审计日志
--       （spec §6 / plan Chunk A Step1，取代未实现的 TokenUsage统计）
-- 设计要点：
--   1. 余额单行/用户：user_points_balance.user_id UNIQUE。balance_points 可负=欠款
--      （预检>0 放行 + 后扣实际 → 瞬时负数合法，下次预检拦）。
--   2. points_ledger append-only：每笔落 balance_after，可正向重建对账。type 四态：
--      RECHARGE(充值)/CONSUME(消耗)/REFUND(退款)/ADMIN_GRANT(管理员发放)。
--   3. payment_order：MVP 仅 admin grant（channel=ADMIN, status 直 PAID）；
--      Phase2 自助支付（ALIPAY/WECHAT）复用本表 + 回调状态机。
--   4. llm_usage_logs = 审计层（admin 看真 token+¥+积分）：kind 四类 CHAT/EMBED/IMAGE/VIDEO。
--      视频/图片积分独立计，不与文本加总。不含 prompt/原文（无 PII）。
--   5. 软删/审计四字段不加：余额/流水/日志皆 append-only 或单行 update，靠 created_at 归档。
-- 两大风险（plan §坑点）：① 流式 usage side-channel 不扰动 Flux；② 并发扣减 UPDATE...RETURNING 行锁防超支。
-- ============================================================

-- 1. 钱包余额（单行/用户，可负=欠款）
CREATE TABLE user_points_balance (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id         BIGINT                   NOT NULL,
    balance_points  NUMERIC(14,2)            NOT NULL DEFAULT 0,         -- 积分余额，可负（预检>0+后扣实际的瞬时欠款）
    updated_at      TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_balance_user UNIQUE (user_id)
);
COMMENT ON TABLE  user_points_balance             IS '积分计费·钱包余额（单行/用户，可负=欠款）。预检>0+后扣实际';
COMMENT ON COLUMN user_points_balance.balance_points IS '积分余额，可负：扣到负后下次预检拦（spec §6 字段说明）';

-- 2. 积分流水（append-only 对账，每笔落当时余额）
CREATE TABLE points_ledger (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at      TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    user_id         BIGINT                   NOT NULL,
    type            VARCHAR(16)              NOT NULL,                    -- RECHARGE/CONSUME/REFUND/ADMIN_GRANT
    delta_points    NUMERIC(14,2)            NOT NULL,                    -- 正=入账（充值/退款/发放），负=扣减
    money_yuan      NUMERIC(12,6),                                         -- 对应金额（消耗时=cost_yuan，充值时=amount）
    ref_type        VARCHAR(16),                                           -- CHAT/EMBED/VIDEO/IMAGE/PAYMENT/ADMIN
    ref_id          BIGINT,                                                -- 关联 id（usage_log id / payment_order id / task id）
    balance_after   NUMERIC(14,2)            NOT NULL,                    -- 本笔后余额（可正向重建对账）
    remark          VARCHAR(256),
    CONSTRAINT chk_ledger_type CHECK (type IN ('RECHARGE','CONSUME','REFUND','ADMIN_GRANT'))
);
CREATE INDEX idx_ledger_user_time ON points_ledger(user_id, created_at);
COMMENT ON TABLE  points_ledger              IS '积分计费·流水（append-only 对账）。每笔落 balance_after 可正向重建';
COMMENT ON COLUMN points_ledger.type         IS 'RECHARGE充值/CONSUME消耗/REFUND退款/ADMIN_GRANT管理员发放';
COMMENT ON COLUMN points_ledger.delta_points IS '正=入账（充值/退款/发放），负=扣减';
COMMENT ON COLUMN points_ledger.balance_after IS '本笔后余额（对账基准）';

-- 3. 充值订单（admin grant MVP / 自助支付 Phase2）
CREATE TABLE payment_order (
    id               BIGINT                  GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at       TIMESTAMPTZ             NOT NULL DEFAULT NOW(),
    user_id          BIGINT                  NOT NULL,
    amount_yuan      NUMERIC(12,2)           NOT NULL,                    -- 充值金额（¥）
    points_granted   NUMERIC(14,2),                                       -- 实到积分（阶梯折算后）
    status           VARCHAR(16)             NOT NULL DEFAULT 'PENDING',  -- PENDING/PAID/FAILED/REFUNDED
    channel          VARCHAR(16)             NOT NULL,                    -- ADMIN/ALIPAY/WECHAT
    channel_order_id VARCHAR(128),                                         -- 支付渠道订单号（Phase2 幂等：UNIQUE）
    paid_at          TIMESTAMPTZ,
    CONSTRAINT chk_pay_status CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED')),
    CONSTRAINT chk_pay_channel CHECK (channel IN ('ADMIN','ALIPAY','WECHAT'))
);
CREATE INDEX idx_payment_user_time ON payment_order(user_id, created_at);
-- Phase2 自助支付回调幂等：取消下行注释启用渠道订单号唯一约束
-- CREATE UNIQUE INDEX uk_payment_channel_order ON payment_order(channel_order_id) WHERE channel_order_id IS NOT NULL;
COMMENT ON TABLE  payment_order             IS '积分计费·充值订单。MVP 仅 admin grant(channel=ADMIN 直 PAID)；Phase2 自助支付补回调流转';
COMMENT ON COLUMN payment_order.channel     IS 'ADMIN(MVP管理员发放)/ALIPAY/WECHAT(Phase2)';

-- 4. LLM 调用审计日志（admin 看真 token+¥+积分；用户侧不暴露 token/¥）
CREATE TABLE llm_usage_logs (
    id                BIGINT                GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_at        TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    user_id           BIGINT,                                            -- nullable：系统调用无 user（仍采不扣）
    provider_id       BIGINT                NOT NULL,                     -- llm_providers.id / user_llm_providers.id
    provider_scope    VARCHAR(8)            NOT NULL DEFAULT 'GLOBAL',    -- GLOBAL/USER
    model             VARCHAR(128)          NOT NULL,
    kind              VARCHAR(8)            NOT NULL,                     -- CHAT/EMBED/IMAGE/VIDEO
    tokens_input      INTEGER               NOT NULL DEFAULT 0,
    tokens_output     INTEGER               NOT NULL DEFAULT 0,
    cost_yuan         NUMERIC(12,6),                                      -- 当时算的真实金额（价表改不动历史）
    points_consumed   NUMERIC(14,2),                                     -- 当时折算积分（与 ledger 互证）
    status            VARCHAR(16)           NOT NULL DEFAULT 'SUCCESS',   -- SUCCESS/FAILED/ESTIMATED
    error_msg         VARCHAR(256),
    CONSTRAINT chk_usage_kind CHECK (kind IN ('CHAT','EMBED','IMAGE','VIDEO')),
    CONSTRAINT chk_usage_status CHECK (status IN ('SUCCESS','FAILED','ESTIMATED'))
);
CREATE INDEX idx_usage_user_time   ON llm_usage_logs(user_id, created_at);
CREATE INDEX idx_usage_provider_tm ON llm_usage_logs(provider_scope, provider_id, created_at);
CREATE INDEX idx_usage_model_time  ON llm_usage_logs(model, created_at);
COMMENT ON TABLE  llm_usage_logs             IS '积分计费·LLM 调用审计层（admin 看真 token+¥+积分）。不含 prompt/原文，无 PII';
COMMENT ON COLUMN llm_usage_logs.kind        IS '模型大类：CHAT/EMBED/IMAGE/VIDEO。视频图片积分独立计，不与文本加总';
COMMENT ON COLUMN llm_usage_logs.cost_yuan   IS '当时算的真实金额；价表后续改不动历史（按 effective_from 取调用时价）';

-- ============================================================
-- 回滚（rollback）：
-- DROP TABLE IF EXISTS llm_usage_logs;
-- DROP TABLE IF EXISTS payment_order;
-- DROP TABLE IF EXISTS points_ledger;
-- DROP TABLE IF EXISTS user_points_balance;
-- ============================================================
