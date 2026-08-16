-- V128 · 记忆判定域模型脱离死值（2026-08-17 用户实测①第三轮）
--
-- 背景：getMemoryJudgeModel() 此前硬回退全局默认对话模型（glm-5.1）。glm 系忽略
-- thinking:disabled 且思考与正文共享 max_tokens 预算 → 内部 JSON 调用随机被思考
-- 吃满截断（用户文件记忆 java 课件 3 连败：usage 输出精确=800→2048 均截断）。
-- 代码侧已改为优先读 memory.judge.model；本迁移把历史 seed 的死值（doubao 2.0/2.1，
-- 火山端点 404 已证不可用）换成 k3（kimi，实证尊重关思考参数、JSON 干净）。
-- 仅替换死值——管理员已显式配置成其他模型则不动。
UPDATE system_settings
SET setting_value = 'k3', updated_at = now()
WHERE setting_key = 'memory.judge.model'
  AND setting_value IN ('doubao-seed-2.0-code', 'doubao-seed-2.1-code');
