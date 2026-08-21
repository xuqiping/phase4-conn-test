-- ============================================================
-- V140: 自助充值支付（7x#1 充值记录六字段 / 7x#3 支付接入 / 20x#1 用户余额视图）
-- 功能：
--   1. payment_order +3 列：payer_account（渠道付款账号，记录六字段之一）、
--      expire_at（PENDING 过期时间，过期 job 批量关单）、idem_key（前端幂等键防双击重复下单）。
--   2. status CHECK 放宽 +CLOSED（用户取消/过期关闭；终态不可再入账）。
--   3. channel CHECK 放宽 +MOCK（mock 支付通道：全链路可测不接真钱；
--      prod 环境 billing.payment.mock-enabled=true 启动即炸，见 PaymentConfigGuard）。
--   4. 幂等三道之第一/三道：
--      - uk_payment_channel_order(channel, channel_order_id) 部分唯一——渠道回调重推撞索引；
--        部分谓词：ADMIN 行 channel_order_id 恒 NULL 不受限。
--      - uk_payment_idem(user_id, idem_key) 部分唯一——同键同金额返原单，同键不同金额 409。
--      （第二道 uq_ledger_ref(ref_type,ref_id,type) V92 已有：RECHARGE 流水一单一行。）
--   5. 查询索引：idx_payment_pending_expire（过期 job 单批扫 PENDING）、
--      idx_payment_user_status_time（me/recharges 分页）、idx_payment_status_time（admin 筛选）。
--   6. V93 拼写修复：V93 对 "payment_orders"（复数，表不存在）做 REVOKE——role agent_app
--      存在时炸迁移。此处对正确表 payment_order（单数）补 REVOKE DELETE/TRUNCATE（订单永不可删）。
-- 回滚（需先停应用）：
--   DROP INDEX IF EXISTS uk_payment_channel_order, uk_payment_idem,
--        idx_payment_pending_expire, idx_payment_user_status_time, idx_payment_status_time;
--   ALTER TABLE payment_order DROP COLUMN payer_account, DROP COLUMN expire_at, DROP COLUMN idem_key;
--   ALTER TABLE payment_order DROP CONSTRAINT chk_pay_status;
--   ALTER TABLE payment_order ADD CONSTRAINT chk_pay_status CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED'));
--   （注意：存在 CLOSED/MOCK 行时 CHECK 回滚会失败，须先清算数据；channel CHECK 同理）
-- ============================================================

ALTER TABLE payment_order ADD COLUMN payer_account VARCHAR(128);
ALTER TABLE payment_order ADD COLUMN expire_at    TIMESTAMPTZ;
ALTER TABLE payment_order ADD COLUMN idem_key     VARCHAR(64);

COMMENT ON COLUMN payment_order.payer_account IS '渠道付款账号（7x#1 记录字段；ADMIN 行 NULL 显「—」；日志须掩码）';
COMMENT ON COLUMN payment_order.expire_at    IS 'PENDING 过期时间（下单+30min 可配）；过期 job 扫此列批量 CLOSED';
COMMENT ON COLUMN payment_order.idem_key     IS '前端幂等键（UUID/表单会话）；uk_payment_idem 防双击/重试重复下单';

-- CLOSED 状态 + MOCK 渠道（PG 不支持 ALTER CONSTRAINT 加值，DROP 再 ADD；既有行全合法无损）
ALTER TABLE payment_order DROP CONSTRAINT chk_pay_status;
ALTER TABLE payment_order ADD CONSTRAINT chk_pay_status
    CHECK (status IN ('PENDING','PAID','FAILED','REFUNDED','CLOSED'));
ALTER TABLE payment_order DROP CONSTRAINT chk_pay_channel;
ALTER TABLE payment_order ADD CONSTRAINT chk_pay_channel
    CHECK (channel IN ('ADMIN','ALIPAY','WECHAT','MOCK'));

-- 建唯一索引前防御性查重：理论上现网仅 ADMIN 行（channel_order_id NULL）不参与；
-- 若有手工灌入的重复渠道单号，宁可迁移炸掉暴露脏数据，也不带病建索引
DO $$
BEGIN
    IF EXISTS (
        SELECT channel, channel_order_id FROM payment_order
        WHERE channel_order_id IS NOT NULL
        GROUP BY channel, channel_order_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'payment_order 存在重复 (channel, channel_order_id)，先清洗再迁移';
    END IF;
END $$;

CREATE UNIQUE INDEX uk_payment_channel_order
    ON payment_order(channel, channel_order_id) WHERE channel_order_id IS NOT NULL;
CREATE UNIQUE INDEX uk_payment_idem
    ON payment_order(user_id, idem_key) WHERE idem_key IS NOT NULL;

-- 过期 job 单批扫描（部分索引防全表扫；PENDING 是极少数行）
CREATE INDEX idx_payment_pending_expire ON payment_order(expire_at) WHERE status = 'PENDING';
-- 用户侧充值记录分页（六字段列表按时间倒序）
CREATE INDEX idx_payment_user_status_time ON payment_order(user_id, status, created_at);
-- admin 记录筛选（渠道/状态/日期）
CREATE INDEX idx_payment_status_time ON payment_order(status, created_at);

-- V93 拼写修复：对正确表 payment_order（单数）补 REVOKE（订单永不可删）；role 不存在跳过（本地 dev 超管）
DO $$
BEGIN
    IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'agent_app') THEN
        EXECUTE 'REVOKE DELETE, TRUNCATE ON payment_order FROM agent_app';
        RAISE NOTICE 'payment_order REVOKE DELETE,TRUNCATE applied to role agent_app（V93 复数拼写修正）';
    ELSE
        RAISE NOTICE 'role agent_app 不存在，跳过 payment_order REVOKE';
    END IF;
END $$;
