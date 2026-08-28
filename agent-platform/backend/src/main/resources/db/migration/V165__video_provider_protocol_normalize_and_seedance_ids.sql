-- 视频模型接入扩展 RG/MVR-7：VIDEO 存量行协议归一 + ark 行补官方 Seedance 2.0 模型 ID。
--
-- ① 协议归一（补 V163 漏网）：V163 只回填 protocol 为 NULL/'' 的行；历史上模型管理页
--   VIDEO 行的协议字段被隐藏，创建时落库 chat 默认值 'OPENAI_COMPATIBLE'——不在 V163
--   命中范围。该值是 worker 路由键（protocol → MediaGenProvider 适配器 bean），
--   map.get("OPENAI_COMPATIBLE") 无注册适配器 → 既有视频任务会「协议未注册」失败。
--   本迁移前平台视频协议事实上只有 ark，故非 {ark, minimax, dashscope} 一律归一 'ark'。
--
-- ② 官方模型 ID 入库（不猜 ID）：方舟官方模型列表（文档 82379/1330310，2026-05.29 版）
--   「视频生成能力」确认存在的 2.0 系 ID 仅两个：
--     doubao-seedance-2-0-260128        480p/720p/1080p，4~15s，音画同生
--     doubao-seedance-2-0-fast-260128   480p/720p，4~15s，音画同生
--   追加进 ark 行 models 后，前端模型下拉与价表候选自动联动（能力层前缀匹配已就绪）。
--   mini / 2.5 官方列表未列（无权威 ID 字符串）——不猜 ID 入库，由管理员从方舟控制台
--   复制后手工加进 models 列表，步骤见 user-ops 积分计费手册 2026-08-28 增补。
--
-- 回滚（手册记档）：
--   UPDATE llm_providers SET protocol='OPENAI_COMPATIBLE'
--     WHERE category='VIDEO' AND protocol='ark';
--   UPDATE llm_providers SET models = (models::jsonb
--     - 'doubao-seedance-2-0-260128' - 'doubao-seedance-2-0-fast-260128')::text
--     WHERE category='VIDEO' AND protocol='ark';

-- ① 协议归一（幂等：目标值不在白名单外再改）
UPDATE llm_providers SET protocol='ark'
WHERE category='VIDEO' AND (protocol IS NULL OR protocol NOT IN ('ark', 'minimax', 'dashscope'));

-- ② 官方 ID 追加（幂等：jsonb ? 判存在才跳过；|| 数组拼接保留原序追加尾部）
UPDATE llm_providers
SET models = (models::jsonb || '["doubao-seedance-2-0-260128"]'::jsonb)::text
WHERE category='VIDEO' AND protocol='ark'
  AND models LIKE '[%'
  AND NOT (models::jsonb ? 'doubao-seedance-2-0-260128');

UPDATE llm_providers
SET models = (models::jsonb || '["doubao-seedance-2-0-fast-260128"]'::jsonb)::text
WHERE category='VIDEO' AND protocol='ark'
  AND models LIKE '[%'
  AND NOT (models::jsonb ? 'doubao-seedance-2-0-fast-260128');
