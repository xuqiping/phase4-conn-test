-- 将旧模块默认模型迁移为管理员可配置项；值从当前启用供应商动态取得，不写死任何模型 ID。
INSERT INTO system_settings (setting_key, setting_value, description)
SELECT 'llm.default.chat-model', candidate.model,
       '管理员配置的全局默认对话模型；调用未显式选择时使用'
FROM (
    SELECT COALESCE(
        (SELECT ss.setting_value
         FROM system_settings ss
         WHERE ss.setting_key = 'memory.judge.model'
           AND EXISTS (
               SELECT 1 FROM llm_providers p
               WHERE p.status = 'ACTIVE' AND p.deleted = 0 AND p.category = 'CHAT'
                 AND p.models::jsonb ? ss.setting_value
           )),
        (SELECT p.models::jsonb ->> 0
         FROM llm_providers p
         WHERE p.status = 'ACTIVE' AND p.deleted = 0 AND p.category = 'CHAT'
           AND jsonb_array_length(p.models::jsonb) > 0
         ORDER BY p.sort_order, p.id LIMIT 1)
    ) AS model
) candidate
WHERE candidate.model IS NOT NULL
ON CONFLICT (setting_key) DO NOTHING;

INSERT INTO system_settings (setting_key, setting_value, description)
SELECT 'llm.default.embedding-model', p.models::jsonb ->> 0,
       '管理员配置的全局默认向量模型；调用未显式选择时使用'
FROM llm_providers p
WHERE p.status = 'ACTIVE' AND p.deleted = 0 AND p.category = 'EMBEDDING'
  AND jsonb_array_length(p.models::jsonb) > 0
ORDER BY p.sort_order, p.id
LIMIT 1
ON CONFLICT (setting_key) DO NOTHING;

-- 旧记忆专用默认不再作为运行时真相，保留行仅用于历史审计。
UPDATE system_settings
SET description = '已废弃：记忆任务统一使用 llm.default.chat-model 或源对话显式模型',
    updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'memory.judge.model';
