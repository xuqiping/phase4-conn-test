-- ============================================================
-- V66: 积分计费系统 · 价表 + 阶梯比例 + 权限 seed
-- 功能：模型价表（多形态）+ ¥→积分阶梯比例 + billing 三权限（admin 默认有）
--       （spec §6 / plan Chunk A Step1 + Chunk B 权限）
-- 设计要点：
--   1. pricing_rule 多形态：kind=CHAT/EMBED/IMAGE/VIDEO。
--      文本: price_input/output_per_million（每 1M token，¥）；
--      视频: video_billing_mode TOKEN|SECOND + price_per_second；
--      图片: price_per_image（按张）。
--      provider_id 可空=全局价；非空=该 provider 专属价（命中优先于全局）。
--   2. effective_from 价/比例生效起点：改价写新行，旧流水不动；询价取 <=now 最新。
--   3. points_ratio_tier 阶梯比例：min<=¥<(max||∞) 命中，ratio=每¥换多少积分。
--      充值与消耗共用一套比例。
--   4. 权限 gated：pricing:manage / points:recharge / usage:view 仅 admin 默认有
--      （同 V54 media:gen / V55 canvas:write / V58 asset:write 模式）。
--      用户自助 /api/billing/me/** 不挂 @RequirePermission（JWT + ownership 硬过滤）。
-- ============================================================

-- 5. 模型价表（多形态，admin 配）
CREATE TABLE pricing_rule (
    id                         BIGINT             GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    kind                       VARCHAR(8)         NOT NULL,                -- CHAT/EMBED/IMAGE/VIDEO
    provider_id                BIGINT,                                     -- null=全局价；非空=该 provider 专属价
    model                      VARCHAR(128)       NOT NULL,
    price_input_per_million    NUMERIC(12,6),                              -- 文本/embed：每 1M input token（¥）
    price_output_per_million   NUMERIC(12,6),                              -- 文本：每 1M output token（¥）
    video_billing_mode         VARCHAR(8),                                 -- 视频：TOKEN|SECOND
    price_per_second           NUMERIC(12,6),                              -- 视频 SECOND 模式：每秒（¥）
    price_per_image            NUMERIC(12,6),                              -- 图片：每张（¥）
    effective_from             TIMESTAMPTZ        NOT NULL DEFAULT NOW(),  -- 生效起点，历史不回改
    CONSTRAINT chk_pricing_kind CHECK (kind IN ('CHAT','EMBED','IMAGE','VIDEO')),
    CONSTRAINT chk_video_mode CHECK (video_billing_mode IS NULL OR video_billing_mode IN ('TOKEN','SECOND'))
);
-- 询价主索引：先缩圈 kind+model，provider 专属优先（查询时 ORDER BY provider_id NULLS LAST）
CREATE INDEX idx_pricing_lookup ON pricing_rule(kind, model, effective_from DESC);
COMMENT ON TABLE  pricing_rule                          IS '积分计费·模型价表（多形态，admin 配）。改价写新行，旧流水不动';
COMMENT ON COLUMN pricing_rule.provider_id              IS 'null=全局价；非空=provider 专属价（命中优先于全局）';
COMMENT ON COLUMN pricing_rule.effective_from           IS '生效起点；询价取 <=now 最新，保证历史流水不漂移';
COMMENT ON COLUMN pricing_rule.video_billing_mode       IS '视频计费：TOKEN(按token, Ark返usage)/SECOND(按秒)';

-- 6. 阶梯比例（¥→积分，admin 配，充值与消耗共用）
CREATE TABLE points_ratio_tier (
    id              BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    min_amount      NUMERIC(12,2)            NOT NULL,                    -- 区间下限（含）
    max_amount      NUMERIC(12,2),                                        -- 区间上限（不含）；null=∞
    ratio           NUMERIC(10,4)            NOT NULL,                    -- 每 ¥ 换多少积分
    effective_from  TIMESTAMPTZ              NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_tier_range CHECK (max_amount IS NULL OR max_amount > min_amount)
);
CREATE INDEX idx_ratio_effective ON points_ratio_tier(effective_from DESC);
COMMENT ON TABLE  points_ratio_tier     IS '积分计费·阶梯比例（¥→积分，充值与消耗共用）。min<=¥<(max||∞) 命中';
COMMENT ON COLUMN points_ratio_tier.ratio IS '每 ¥ 换多少积分（如 100=每¥换100积分）';

-- 7. 权限 seed（gated：仅 admin 默认有；普通 user 由 admin 按需授）
INSERT INTO permissions (name, code, resource, action) VALUES
    ('价表管理', 'pricing:manage', 'billing', 'manage'),
    ('积分充值', 'points:recharge', 'billing', 'recharge'),
    ('用量查询', 'usage:view',     'billing', 'view')
ON CONFLICT DO NOTHING;

INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id
FROM roles r
CROSS JOIN permissions p
WHERE r.code = 'admin'
  AND p.code IN ('pricing:manage', 'points:recharge', 'usage:view')
ON CONFLICT DO NOTHING;

-- ============================================================
-- 回滚（rollback）：
-- DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code IN ('pricing:manage','points:recharge','usage:view'));
-- DELETE FROM permissions WHERE code IN ('pricing:manage','points:recharge','usage:view');
-- DROP TABLE IF EXISTS points_ratio_tier;
-- DROP TABLE IF EXISTS pricing_rule;
-- ============================================================
