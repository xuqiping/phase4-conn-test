-- V164 · 视频SECOND计价按分辨率分档（MVR-3，规格：docs/specs/视频模型接入扩展.md）
-- 背景：按秒计费的视频模型（如 Hailuo/Minimax、HappyHorse）不同分辨率秒价不同（768p 一档、2k 一档），
-- 原单值 price_per_second 表达不了。本列给 VIDEO+SECOND 行一行内多档存秒价。
--
-- 比喻：SECOND 价表行=计时停车场，通用秒价=基础每分钟价，本列=「不同车型价目表」——
-- 大车（2k）一个价、小车（768p）一个价，没单列的车型都按基础价（回落 price_per_second）。
--
-- 约定：
--   * 键 ⊆ {480p, 720p, 768p, 1080p, 2k, 4k}（键归一：trim+小写，4K→4k），无 general 键——通用秒价复用既有列
--   * 值 = ¥/秒（正数）；服务层白名单+正数校验（同 V162 token_price_per_resolution 先例，不加 DB CHECK）
--   * 仅 VIDEO+video_billing_mode=SECOND 行有意义；TOKEN/CHAT/EMBED/RERANK/IMAGE 恒 NULL
--   * 与 est_per_resolution（TOKEN 预估 ¥/秒）、token_price_per_resolution（TOKEN 扣费 ¥/百万）三列同表同形态，
--     互不派生——est 槽只管 TOKEN 提交期预检，本槽只管 SECOND 真实扣费/估价
-- 无数据迁移：存量行槽位 NULL = 全按通用秒价，行为逐字节不变（回落语义保证零迁移）。
-- 回滚：ALTER TABLE pricing_rule DROP COLUMN price_per_second_per_resolution;
ALTER TABLE pricing_rule
  ADD COLUMN price_per_second_per_resolution JSONB;
