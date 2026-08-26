-- V160：7x-4/7x-5/9x-1 价表三改（人工测试遗留问题修复II D1）。
-- ① 闲时价三列 + 缓存价一列（全 NULL 默认 = 存量行为逐分不变：off_peak_*=NULL 取忙时列，
--    price_cached=NULL 缓存价=输入价；cached_tokens=NULL 时计费退化为两腿）。
-- ② llm_usage_logs 加 cached_tokens（缓存命中读 token，计费第三腿 + 调用明细展示）。
-- ③ SECOND 视频价去分辨率：resolution 维度废除（Q3=A 彻底移除）。
--    按 (provider_id, model, has_reference) 分组，每组只保留 effective_from 最新（同刻取 id 大）
--    一行并 resolution=NULL（通用行）；其余物理删除（偏差记录：pricing_rule 无 deleted 列，
--    append-only 表不引入逻辑删除——被删行的价差清单以 NOTICE 打进迁移日志留痕，历史流水
--    cost_yuan 已快照不受影响）。
-- ④ 询价索引去 resolution 段。
-- 注意：TOKEN 模式 est_per_resolution JSONB（V153）保留不动——提交期估价预检仍按分辨率估。
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS off_peak_input_per_million  NUMERIC(12,6);
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS off_peak_output_per_million NUMERIC(12,6);
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS off_peak_cached_per_million NUMERIC(12,6);
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS price_cached_per_million    NUMERIC(12,6);

COMMENT ON COLUMN pricing_rule.off_peak_input_per_million  IS '闲时输入价 ¥/1M tokens；NULL=同忙时（price_input_per_million）。仅 CHAT/EMBED/RERANK';
COMMENT ON COLUMN pricing_rule.off_peak_output_per_million IS '闲时输出价 ¥/1M tokens；NULL=同忙时。仅 CHAT';
COMMENT ON COLUMN pricing_rule.off_peak_cached_per_million IS '闲时缓存命中价 ¥/1M tokens；NULL=闲时缓存价回落闲时缓存价规则（price_cached→输入价）。仅 CHAT';
COMMENT ON COLUMN pricing_rule.price_cached_per_million    IS '缓存命中读 token 价 ¥/1M；NULL=同输入价（缓存不省钱则不单配）。仅 CHAT';

ALTER TABLE llm_usage_logs
    ADD COLUMN IF NOT EXISTS cached_tokens BIGINT;

COMMENT ON COLUMN llm_usage_logs.cached_tokens IS '缓存命中读 token 数（OpenAI=prompt_tokens_details.cached_tokens；Claude=cache_read_input_tokens）；NULL=未上报/不支持，计费退化为输入+输出两腿';

-- ③ SECOND 去分辨率合并：生活比喻——原来 1080p/720p/480p 各有一本价签挂在同一商品上，
-- 现在撤掉分档，每 (供应商×模型×有无参考) 只留最新一本价签（通用行）。
DO $$
DECLARE
    g RECORD;
    r RECORD;
    keeper_id BIGINT;
    group_count INT := 0;
    drop_count INT := 0;
BEGIN
    FOR g IN
        SELECT provider_id, model, has_reference
        FROM pricing_rule
        WHERE kind = 'VIDEO' AND video_billing_mode = 'SECOND'
        GROUP BY provider_id, model, has_reference
    LOOP
        group_count := group_count + 1;

        -- 组内保留者：最新生效（同刻 id 大）
        SELECT id INTO keeper_id
        FROM pricing_rule
        WHERE kind = 'VIDEO' AND video_billing_mode = 'SECOND'
          AND provider_id IS NOT DISTINCT FROM g.provider_id
          AND model = g.model
          AND has_reference IS NOT DISTINCT FROM g.has_reference
        ORDER BY effective_from DESC, id DESC
        LIMIT 1;

        -- 价差留痕：被并行先打进迁移日志（Flyway 日志可查），再删
        FOR r IN
            SELECT id, resolution, price_per_second, effective_from
            FROM pricing_rule
            WHERE kind = 'VIDEO' AND video_billing_mode = 'SECOND'
              AND provider_id IS NOT DISTINCT FROM g.provider_id
              AND model = g.model
              AND has_reference IS NOT DISTINCT FROM g.has_reference
              AND id <> keeper_id
        LOOP
            RAISE NOTICE 'V160 SECOND 合并删除: pricing_rule id=% res=% price/s=% effective_from=% → 保留 id=%',
                r.id, r.resolution, r.price_per_second, r.effective_from, keeper_id;
            drop_count := drop_count + 1;
        END LOOP;

        DELETE FROM pricing_rule
        WHERE kind = 'VIDEO' AND video_billing_mode = 'SECOND'
          AND provider_id IS NOT DISTINCT FROM g.provider_id
          AND model = g.model
          AND has_reference IS NOT DISTINCT FROM g.has_reference
          AND id <> keeper_id;

        -- 保留行去分辨率身份（变通用行；带 resolution 的存量在途结算回落此行）
        UPDATE pricing_rule SET resolution = NULL WHERE id = keeper_id;
    END LOOP;

    RAISE NOTICE 'V160 SECOND 去分辨率完成: 组数=% 删除行=%', group_count, drop_count;
END $$;

-- ④ 询价索引去 resolution 段（残留 resolution 请求走通用行兜底）
DROP INDEX IF EXISTS idx_pricing_lookup;
CREATE INDEX idx_pricing_lookup
    ON pricing_rule (kind, model, has_reference, effective_from DESC);

-- ============================================================
-- 回滚（rollback）：Flyway 不自动回滚，手工执行需谨慎
--   ALTER TABLE pricing_rule
--       DROP COLUMN IF EXISTS off_peak_input_per_million,
--       DROP COLUMN IF EXISTS off_peak_output_per_million,
--       DROP COLUMN IF EXISTS off_peak_cached_per_million,
--       DROP COLUMN IF EXISTS price_cached_per_million;
--   ALTER TABLE llm_usage_logs DROP COLUMN IF EXISTS cached_tokens;
--   ② 的已删 SECOND 分辨率行不可恢复（价差清单在迁移日志 NOTICE 中，可据此手工重建）；
--   索引重建回带 resolution 版见 V152。
-- ============================================================
