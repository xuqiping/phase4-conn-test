-- V153：7x 用户反馈——TOKEN 预估秒价改「一行多分辨率参数」（不再单值）+ 清理存量串味字段。
-- ① est_yuan_per_second(NUMERIC) → est_per_resolution(JSONB)：
--    键 ⊆ {general,480p,720p,1080p,4k}（general=通用兜底），值=预估秒价 ¥/秒。
--    TOKEN 行编辑一次配齐各分辨率预估值，无需按分辨率拆行（SECOND 真实计价仍走 resolution 行）。
-- ② 清串味脏数据：updateById 历史上忽略 null 导致模式/类型切换后残留对面字段
--    （如 TOKEN 行残留 price_per_second、VIDEO 行残留 price_output_per_million）。
--    按「字段↔kind/mode 归属」置 NULL（与 PricingConfigService.validate 同口径）。
ALTER TABLE pricing_rule DROP COLUMN IF EXISTS est_yuan_per_second;
ALTER TABLE pricing_rule ADD COLUMN IF NOT EXISTS est_per_resolution JSONB;

COMMENT ON COLUMN pricing_rule.est_per_resolution IS
    'VIDEO TOKEN only: 提交期预估秒价 JSON {general|480p|720p|1080p|4k: ¥/秒}；general 兜底未单列分辨率。仅预检不计费。';

-- 串味清理（幂等，重复执行无害）
UPDATE pricing_rule SET price_per_second = NULL
    WHERE NOT (kind = 'VIDEO' AND video_billing_mode = 'SECOND');
UPDATE pricing_rule SET price_input_per_million = NULL, price_output_per_million = NULL
    WHERE kind NOT IN ('CHAT', 'EMBED', 'RERANK')
      AND NOT (kind = 'VIDEO' AND video_billing_mode = 'TOKEN');
UPDATE pricing_rule SET price_output_per_million = NULL WHERE kind <> 'CHAT';
UPDATE pricing_rule SET price_per_image = NULL WHERE kind <> 'IMAGE';
UPDATE pricing_rule SET resolution = NULL
    WHERE NOT (kind = 'VIDEO' AND video_billing_mode = 'SECOND');
UPDATE pricing_rule SET video_billing_mode = NULL WHERE kind <> 'VIDEO';
