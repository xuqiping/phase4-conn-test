-- V152：7x 未解决 #1/#2——视频价表加「分辨率」维度 + TOKEN 模式提交期估价字段。
-- 问题1：SECOND 模式不同分辨率成本不同（1080p≠720p），需按 (分辨率×有无参考) 分行配价。
--   resolution：仅 VIDEO SECOND 行有意义；NULL=通用行（未单列的分辨率任务回落此行，与
--   has_reference=false 兜底同范式）。CHAT/EMBED/RERANK/IMAGE/VIDEO TOKEN 行恒 NULL。
-- 问题2：消耗前管控（余额≥预估才放行）。估价口径复用价表（SECOND=分辨率秒价×时长，
--   IMAGE=张价×张数）；TOKEN 模式提交期无 token 维度 → est_yuan_per_second 补「预估秒价」，
--   仅用于提交期估价预检，真实扣费仍按 Ark 返的 total_tokens。
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS resolution VARCHAR(16);

ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS est_yuan_per_second NUMERIC(12,6);

-- 询价索引带分辨率判别（同 idx_pricing_lookup 范式，追加列不破坏旧查询）
DROP INDEX IF EXISTS idx_pricing_lookup;
CREATE INDEX idx_pricing_lookup
    ON pricing_rule (kind, model, has_reference, resolution, effective_from DESC);

COMMENT ON COLUMN pricing_rule.resolution IS
    'VIDEO SECOND only: 480p/720p/1080p/4K; NULL = 通用行（未单列分辨率的兜底价）。';
COMMENT ON COLUMN pricing_rule.est_yuan_per_second IS
    'VIDEO TOKEN only: 提交期预估秒价 ¥/秒（估价预检用，不参与真实扣费）。';
