-- 5x #7：收录确认式回复开关 seed（system_settings，幂等）。
-- 语义：命中确定性收录规则（本人 gen 开项目的文件名硬规则）→ 先模板确认
-- 「你输入的内容已命中项目『X』的收录规则…需要我基于你的输入进行回答吗？」，
-- 用户点「需要回答」才全量回答；false=回旧行为直接全量回答（热关回退，不发版）。
INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('rag.memory.inclusion-confirm.enabled', 'true',
     '收录确认式回复开关：命中确定性收录规则（文件名硬规则）先模板确认，点「需要回答」才全量回答；false=直接全量回答')
ON CONFLICT (setting_key) DO NOTHING;
