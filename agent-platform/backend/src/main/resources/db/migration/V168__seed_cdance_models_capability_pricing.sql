-- V168 · cdance 四模型（2.0 / 2.0-fast / 2.0-mini / 2.5）配置 + 价表入库
-- 背景：dev 库这三样是管理员经"设置→全局模型供应商"手工配的（模型别名、capabilities
-- 四模型口径、价表空行），只存在于 dev 库——新库 Flyway 跑完 V165/V166 后 seedance
-- provider 挂的是 doubao-seedance-2-0-* 官方 ID（经 ctaigw 中转调不通，dev 已手工
-- 摘除），cdance 能力与价表则完全缺失。本迁移把 dev 库现状固化进版本：
--   ① provider.models：摘 doubao 官方 ID（中转不走）→ 并入 cdance 中转别名四个
--   ② provider.config.capabilities：四模型完整口径（参考图 9/9/9/30、视频 3/3/3/10、
--      音频 3/3/3/10、附件 12/12/12/50；分辨率 2.0/2.5 到 4K、fast/mini 仅 480p/720p；
--      时长 2.0 系 4~15s、2.5 4~30s；音画同生 + videoDataUri）
--   ③ 价表四行：TOKEN 计费，价按库内唯一存量 datum——旧 Cdance2.0 行
--      price_output_per_million=58 先填齐（占位防"无价表不可用"），具体中转价
--      由管理员后续在 价表配置 页调整。has_reference=t（四模型均支持多参考图）。
-- 幂等：模型数组 jsonb 增删判存、capabilities jsonb || 合并、价表 kind+model+provider 判重。
--
-- 回滚（手册记档）：
--   UPDATE llm_providers SET models='[]', config=config-'capabilities' WHERE name='seedance';
--   DELETE FROM pricing_rule WHERE kind='VIDEO' AND provider_id=
--     (SELECT id FROM llm_providers WHERE name='seedance');

-- ① models：摘 doubao 官方 ID、并入 cdance 别名并去重保序（幂等）。
--    注意 jsonb 数组 || 是拼接不是并集，重复执行/存量已有别名会翻倍——
--    以 v::text 作等值键 GROUP BY 去重（jsonb 自身无 btree，不能直接 DISTINCT）。
UPDATE llm_providers
SET models = ((
  SELECT jsonb_agg(v ORDER BY ord)
  FROM (
    SELECT DISTINCT ON (v::text) v, ord
    FROM jsonb_array_elements(
      (models::jsonb
         - 'doubao-seedance-2-0-260128'
         - 'doubao-seedance-2-0-fast-260128')
      || '["cdance2.0-0611","cdance2.0-fast-0611","cdance2.0-mini-0611","cdance2.5-0807"]'::jsonb
    ) WITH ORDINALITY AS t(v, ord)
    ORDER BY v::text, ord
  ) s
))::text
WHERE name = 'seedance' AND category = 'VIDEO';

-- ② capabilities 四模型口径（jsonb || 深合并保留 config 其余键，幂等）
UPDATE llm_providers
SET config = (config::jsonb || '{
  "capabilities": {
    "cdance2.0-0611": {
      "maxImages": 9, "maxVideos": 3, "maxAudios": 3, "maxAttachments": 12,
      "supportedRatios": ["21:9","16:9","4:3","1:1","3:4","9:16","adaptive"],
      "supportedResolutions": ["480p","720p","1080p","4K"],
      "minDuration": 4, "maxDuration": 15,
      "supportsGenerateAudio": true, "videoDataUri": true
    },
    "cdance2.0-fast-0611": {
      "maxImages": 9, "maxVideos": 3, "maxAudios": 3, "maxAttachments": 12,
      "supportedRatios": ["21:9","16:9","4:3","1:1","3:4","9:16","adaptive"],
      "supportedResolutions": ["480p","720p"],
      "minDuration": 4, "maxDuration": 15,
      "supportsGenerateAudio": true, "videoDataUri": true
    },
    "cdance2.0-mini-0611": {
      "maxImages": 9, "maxVideos": 3, "maxAudios": 3, "maxAttachments": 12,
      "supportedRatios": ["21:9","16:9","4:3","1:1","3:4","9:16","adaptive"],
      "supportedResolutions": ["480p","720p"],
      "minDuration": 4, "maxDuration": 15,
      "supportsGenerateAudio": true, "videoDataUri": true
    },
    "cdance2.5-0807": {
      "maxImages": 30, "maxVideos": 10, "maxAudios": 10, "maxAttachments": 50,
      "supportedRatios": ["21:9","16:9","4:3","1:1","3:4","9:16","adaptive"],
      "supportedResolutions": ["480p","720p","1080p","4K"],
      "minDuration": 4, "maxDuration": 30,
      "supportsGenerateAudio": true, "videoDataUri": true
    }
  }
}'::jsonb)::text
WHERE name = 'seedance' AND category = 'VIDEO';

-- ③ 价表四行（TOKEN 计费，output 58/百万先占位；判重 kind+model+(provider 或未绑定的
--    NULL 行)——dev 库存量四行 provider_id 为 NULL，若只判 provider 会插重复行）
INSERT INTO pricing_rule (kind, provider_id, model, video_billing_mode,
                          price_output_per_million, has_reference)
SELECT 'VIDEO', p.id, m.model, 'TOKEN', 58.0, TRUE
FROM llm_providers p
JOIN (VALUES ('cdance2.0-0611'), ('cdance2.0-fast-0611'),
             ('cdance2.0-mini-0611'), ('cdance2.5-0807')) AS m(model) ON TRUE
WHERE p.name = 'seedance' AND p.category = 'VIDEO'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'VIDEO' AND r.model = m.model
                    AND (r.provider_id = p.id OR r.provider_id IS NULL));

-- ③' 存量空行补绑/补价（dev 库已有 provider_id 为 NULL、价格全空的四行——绑 provider
--     并填占位价，不动管理员已填过的值）
UPDATE pricing_rule r
SET provider_id = p.id,
    price_output_per_million = COALESCE(r.price_output_per_million, 58.0),
    has_reference = TRUE
FROM llm_providers p
WHERE p.name = 'seedance' AND p.category = 'VIDEO'
  AND r.kind = 'VIDEO' AND r.provider_id IS NULL
  AND r.model IN ('cdance2.0-0611', 'cdance2.0-fast-0611', 'cdance2.0-mini-0611', 'cdance2.5-0807');
