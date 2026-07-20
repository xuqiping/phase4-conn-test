-- V45: M3 entities 词袋计数配置（原待办 #19）。
-- 控制 MemoryConflictJudge 抽取 prompt 数量指引 + Java readEntities 兜底截断阈值。
-- JSON 字段：totalMax / variantMin-Max / properNounMin-Max / hypernymMin-Max。
-- 默认值 = V38 硬上限（totalMax=20, variant 1~3, properNoun 1~5, hypernym 5~10），存量行为零变更。
INSERT INTO system_settings (setting_key, setting_value, description)
VALUES ('rag.memory.entities-config',
        '{"totalMax":20,"variantMin":1,"variantMax":3,"properNounMin":1,"properNounMax":5,"hypernymMin":5,"hypernymMax":10}',
        'entities词袋计数配置（totalMax总数上限/variantMin-Max同义变体/properNounMin-Max专名/hypernymMin-Max上位词，默认V38硬上限）')
ON CONFLICT (setting_key) DO NOTHING;
