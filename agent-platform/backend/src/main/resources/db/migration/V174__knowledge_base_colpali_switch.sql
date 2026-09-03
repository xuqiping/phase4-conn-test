-- WP5 Step4（spec §7.2 路线 B）：ColPali 页面级视觉嵌入实验通道——KB 级开关。
-- 全局开关在应用配置 rag.visual.colpali.enabled（默认关，sidecar 未部署）；本列仅决定
-- 「全局开的前提下哪些库参与实验」。生活比喻：小区总闸（全局配置）+ 各户分闸（本列），
-- 两闸都合才通电；任一拉下该库检索行为与现在完全一致。
ALTER TABLE knowledge_bases ADD COLUMN IF NOT EXISTS colpali_enabled BOOLEAN NOT NULL DEFAULT FALSE;
