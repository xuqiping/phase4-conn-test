-- V166 · 视频模型接入扩展 II（HHX-1/2/8，规格：docs/specs/视频模型接入扩展II-H3与HappyHorse中转接入.md）
-- 种子：两 provider 行（经 ai.ctaigw.cn 中转）+ 六价表行。api_key_enc 留空=待管理员在
-- 设置→全局模型供应商 填 key（用户要求"仅留 key 我自己填"）。
--
-- 要点：
--   * happyhorse 行必须显式 config.queryEndpoint——Dashscope 适配器默认推导（host+/api/v1/tasks）
--     对中转是错的（中转实际 /v1/tasks，无 /api 前缀），缺失会查询打错地址无限退避。
--   * minimax 行 queryEndpoint 显式化（推导虽正确，显式防漂移）。
--   * 价表六行 provider 维度（子查询取 id）：同模型名未来可多渠道不同价。
--   * minimax-h3-context-ir 用 kind=CHAT（输入/输出分价 5.8/23 每百万 token）——VIDEO 的
--     TOKEN 模式单 token 价表达不了双价。该行会出现在聊天价表页（模型名自解释，可接受）。
--   * 秒价口径=中转价（=官网非折扣目录价；happyhorse i2v 480P ¥0.45 按官网补齐，官网
--     限时 6 折活动价不采纳）。
--
-- 回滚（手册记档）：
--   DELETE FROM pricing_rule WHERE model IN ('minimax-h3','minimax-h3-regeneration',
--     'minimax-h3-context-ir','happyhorse-1.1-t2v','happyhorse-1.1-i2v','happyhorse-1.1-r2v')
--     AND provider_id IN (SELECT id FROM llm_providers WHERE name IN ('minimax-h3','happyhorse'));
--   DELETE FROM llm_providers WHERE name IN ('minimax-h3','happyhorse');

-- ① provider 两行（幂等：name 唯一判重）
INSERT INTO llm_providers (name, display_name, category, protocol, api_endpoint, api_key_enc,
                           models, config, status, sort_order, created_by)
SELECT 'happyhorse', 'HappyHorse 1.1（ai.ctaigw.cn 中转）', 'VIDEO', 'dashscope',
       'https://ai.ctaigw.cn/v1/services/aigc/video-generation/video-synthesis', '',
       '["happyhorse-1.1-t2v","happyhorse-1.1-i2v","happyhorse-1.1-r2v"]'::jsonb,
       '{"queryEndpoint":"https://ai.ctaigw.cn/v1/tasks"}'::jsonb,
       'ACTIVE', 90, 1
WHERE NOT EXISTS (SELECT 1 FROM llm_providers WHERE name = 'happyhorse');

INSERT INTO llm_providers (name, display_name, category, protocol, api_endpoint, api_key_enc,
                           models, config, status, sort_order, created_by)
SELECT 'minimax-h3', 'MiniMax H3（ai.ctaigw.cn 中转）', 'VIDEO', 'minimax',
       'https://ai.ctaigw.cn/v1/video_generation', '',
       '["minimax-h3","minimax-h3-context-ir","minimax-h3-regeneration"]'::jsonb,
       '{"queryEndpoint":"https://ai.ctaigw.cn/v1/query/video_generation"}'::jsonb,
       'ACTIVE', 91, 1
WHERE NOT EXISTS (SELECT 1 FROM llm_providers WHERE name = 'minimax-h3');

-- ② 价表六行（幂等：kind+model+provider 判重；provider_id 子查询绑 provider 维度）
INSERT INTO pricing_rule (kind, provider_id, model, video_billing_mode, price_per_second,
                          price_per_second_per_resolution, has_reference)
SELECT 'VIDEO', p.id, 'minimax-h3', 'SECOND', 0.5, '{"768p":0.5,"2k":0.8}'::jsonb, FALSE
FROM llm_providers p
WHERE p.name = 'minimax-h3'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'VIDEO' AND r.model = 'minimax-h3' AND r.provider_id = p.id);

INSERT INTO pricing_rule (kind, provider_id, model, video_billing_mode, price_per_second,
                          price_per_second_per_resolution, has_reference)
SELECT 'VIDEO', p.id, 'minimax-h3-regeneration', 'SECOND', 0.3, '{"2k":0.3}'::jsonb, FALSE
FROM llm_providers p
WHERE p.name = 'minimax-h3'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'VIDEO' AND r.model = 'minimax-h3-regeneration' AND r.provider_id = p.id);

INSERT INTO pricing_rule (kind, provider_id, model,
                          price_input_per_million, price_output_per_million, has_reference)
SELECT 'CHAT', p.id, 'minimax-h3-context-ir', 5.8, 23, FALSE
FROM llm_providers p
WHERE p.name = 'minimax-h3'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'CHAT' AND r.model = 'minimax-h3-context-ir' AND r.provider_id = p.id);

INSERT INTO pricing_rule (kind, provider_id, model, video_billing_mode, price_per_second,
                          price_per_second_per_resolution, has_reference)
SELECT 'VIDEO', p.id, 'happyhorse-1.1-t2v', 'SECOND', 0.9, '{"720p":0.9,"1080p":1.2}'::jsonb, FALSE
FROM llm_providers p
WHERE p.name = 'happyhorse'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'VIDEO' AND r.model = 'happyhorse-1.1-t2v' AND r.provider_id = p.id);

INSERT INTO pricing_rule (kind, provider_id, model, video_billing_mode, price_per_second,
                          price_per_second_per_resolution, has_reference)
SELECT 'VIDEO', p.id, 'happyhorse-1.1-i2v', 'SECOND', 0.9,
       '{"480p":0.45,"720p":0.9,"1080p":1.2}'::jsonb, FALSE
FROM llm_providers p
WHERE p.name = 'happyhorse'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'VIDEO' AND r.model = 'happyhorse-1.1-i2v' AND r.provider_id = p.id);

INSERT INTO pricing_rule (kind, provider_id, model, video_billing_mode, price_per_second,
                          price_per_second_per_resolution, has_reference)
SELECT 'VIDEO', p.id, 'happyhorse-1.1-r2v', 'SECOND', 0.9, '{"720p":0.9,"1080p":1.2}'::jsonb, FALSE
FROM llm_providers p
WHERE p.name = 'happyhorse'
  AND NOT EXISTS (SELECT 1 FROM pricing_rule r
                  WHERE r.kind = 'VIDEO' AND r.model = 'happyhorse-1.1-r2v' AND r.provider_id = p.id);
