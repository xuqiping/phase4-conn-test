-- V171 · C4 上下文嵌入 + C5 多模态预留（WP3 Step2 / WP5 共用一版）
-- 生活比喻：knowledge_nodes 像图书馆的每页卡片；contextual_text 是卡片上多贴的一张
-- 「这张卡片在整本书里的位置」便利贴（LLM 生成的定位语），检索向量把便利贴内容一起
-- 算进去——散页再也难被「断章取义」；modality 是卡片材质栏（文本/图片），多模态检索用。

-- LLM 定位语（≤50字，该 chunk 在文档中的位置与主题；NULL=纯规则前缀=存量行为）
ALTER TABLE knowledge_nodes ADD COLUMN contextual_text TEXT NULL;

-- 内容形态（WP5 多模态：TEXT/IMAGE…；NULL=纯文本=现状）
ALTER TABLE knowledge_nodes ADD COLUMN modality VARCHAR(16) NULL;

COMMENT ON COLUMN knowledge_nodes.contextual_text IS 'C4 LLM 定位语：索引 embed 文本=规则前缀+定位语+原文；NULL=存量纯规则前缀';
COMMENT ON COLUMN knowledge_nodes.modality IS 'C5 内容形态预留（WP5）：TEXT/IMAGE；NULL=纯文本';
