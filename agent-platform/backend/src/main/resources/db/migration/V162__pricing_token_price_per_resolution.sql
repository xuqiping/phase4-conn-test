-- V162 · 视频TOKEN计价按分辨率分档（规格：docs/specs/视频TOKEN分辨率分档计价.md）
-- 背景：seedance2.0（Ark）视频模型按 token 计费，但每百万价随分辨率不同（480p/720p/1080p/4k），
-- 原单值 price_input_per_million 表达不了。本列给 VIDEO+TOKEN 行一行内多档存价。
--
-- 比喻：价表行=一家店，通用价=基础菜单价，本列=「不同规格加价表」——4K 档一个价、720p 档一个价，
-- 没单列的规格都按基础菜单价（回落 price_input_per_million）。
--
-- 约定：
--   * 键 ⊆ {480p, 720p, 1080p, 4k}（键归一：trim+小写，4K→4k），无 general 键——通用价复用既有列
--   * 值 = ¥/百万 token（正数）；服务层白名单+正数校验（同 V153 est_per_resolution 先例，不加 DB CHECK）
--   * 仅 VIDEO+video_billing_mode=TOKEN 行有意义；SECOND/CHAT/EMBED/RERANK/IMAGE 恒 NULL
--   * resolution 列仍恒 NULL（D6/V160 去分辨率多行口径不变——本列是行内槽位，不恢复多行）
--   * 预估（est_per_resolution ¥/秒）与扣费（本列 ¥/百万）两套独立配置，互不派生
-- 无数据迁移：存量行槽位 NULL = 全按通用价，行为逐字节不变（回落语义保证零迁移）。
-- 回滚：ALTER TABLE pricing_rule DROP COLUMN token_price_per_resolution;
ALTER TABLE pricing_rule
  ADD COLUMN token_price_per_resolution JSONB;
