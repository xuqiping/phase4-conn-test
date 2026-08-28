-- 视频模型接入扩展 MVR-1：存量 VIDEO provider 行补标协议。
-- llm_providers.protocol 是 worker 路由键（protocol → MediaGenProvider 适配器 bean）；
-- 空 protocol 在 worker 内回落 'ark'（本迁移前唯一视频协议），此处显式落标使口径一致、
-- 后续新增 minimax/dashscope 行时不混淆。
-- 回滚（手册记档）：UPDATE llm_providers SET protocol=NULL WHERE category='VIDEO' AND protocol='ark';
UPDATE llm_providers SET protocol='ark'
WHERE category='VIDEO' AND (protocol IS NULL OR protocol='');
