-- ============================================================
-- V143: 支付渠道网页配置（7x 追加——admin 管理后台自填渠道密钥，不再只走环境变量）
--   payment_channel_config：每渠道一行；密钥整体 AES 加密（AesEncryptService，同 LLM Key 管线），
--   明文绝不在库中出现；config_tails 存脱敏回显（****尾4位），读取端点永不解密。
--
-- 设计决策：
--   - 渠道白名单 CHECK（ALIPAY/WECHAT；MOCK 配置走 env 开关，不入本表）；
--   - channel 部分唯一索引（软删行不占坑——虽然本表预期只更不删）；
--   - BaseEntity 六列齐全（updated_by/at 即「谁最后改的密钥」，配合 @AuditLog 双保险）；
--   - env 变量保留作 fallback：SDK 实现落地时解析顺序 DB 优先、env 兜底。
--
-- 回滚（rollback）：
--   DROP TABLE IF EXISTS payment_channel_config;   -- 回到纯 env 配置模式
-- ============================================================

CREATE TABLE payment_channel_config (
    id               BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    -- 渠道码白名单（CHECK 硬卡；新增真实渠道先扩 CHECK 再写码）
    channel          VARCHAR(20) NOT NULL,
    -- AES 加密后的配置 JSON（如 {"appId":"...","privateKey":"..."}）；明文不入库
    config_encrypted TEXT        NOT NULL,
    -- 脱敏回显 JSON（{"appId":"****3f2a"}），GET 端点只出本列，避免读路径解密
    config_tails     VARCHAR(500) NOT NULL DEFAULT '{}',
    created_by       BIGINT,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ,
    deleted          INTEGER      NOT NULL DEFAULT 0,
    version          INTEGER      NOT NULL DEFAULT 0,
    CONSTRAINT chk_payment_channel_config_channel CHECK (channel IN ('ALIPAY','WECHAT'))
);
CREATE UNIQUE INDEX uk_payment_channel_config_channel
    ON payment_channel_config (channel) WHERE deleted = 0;
