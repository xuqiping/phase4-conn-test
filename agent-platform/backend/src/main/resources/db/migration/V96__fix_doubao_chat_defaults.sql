-- 修复豆包 Coding Provider 在「endpoint 按完整 URL 直发」改造后仍保留 base URL 的历史配置。
-- 仅更新精确命中的旧值，不覆盖管理员已自定义的 endpoint/模型。
UPDATE llm_providers
SET api_endpoint = 'https://ark.cn-beijing.volces.com/api/coding/v3/chat/completions',
    models = '["doubao-seed-2.1-code"]',
    updated_at = CURRENT_TIMESTAMP
WHERE name = 'doubao'
  AND category = 'CHAT'
  AND api_endpoint IN (
      'https://ark.cn-beijing.volces.com/api/coding/v3',
      'https://ark.cn-beijing.volces.com/api/coding/v3/'
  );

-- 记忆后台任务只在仍使用退役默认值时随 CHAT Provider 升级。
UPDATE system_settings
SET setting_value = 'doubao-seed-2.1-code',
    updated_at = CURRENT_TIMESTAMP
WHERE setting_key = 'memory.judge.model'
  AND setting_value = 'doubao-seed-2.0-code';
