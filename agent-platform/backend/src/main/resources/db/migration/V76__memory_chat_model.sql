-- V76: 记忆管线 LLM model 跟随对话所选 model —— 数据层。
-- 背景：记忆子系统所有 LLM 任务（路由/蒸馏/生成/压缩/冲突/召回标签）曾硬编码 doubao-seed-2.0-code，
--   不随对话所选 model 切换。现改为：请求域透传对话 model；后台域读源 turn/entry 记录的 chat_model；
--   缺失/混合回退 system_settings.memory.judge.model（可配，默认 doubao-seed-2.0-code）。
--
-- 变更：
--   ① memory_turns 加 chat_model：记录该轮对话所用的对话 model（写入时从 ChatRequest.model 落库）。
--   ② memory_project_entries 加 chat_model：条目沿用其源 turn 的 model（后台压缩按条目 model 取）。
--   ③ system_settings seed memory.judge.model：后台域 / 无 model 边缘 case 的可配默认。
-- 两列均可空（存量行 NULL = 回退默认；新行按对话 model 填）。
-- 不动 embedding（MEMORY_EMBED_MODEL 固定，独立 EMBEDDING provider 路由，换则破坏向量空间）。

ALTER TABLE memory_turns ADD COLUMN IF NOT EXISTS chat_model VARCHAR(64);
ALTER TABLE memory_project_entries ADD COLUMN IF NOT EXISTS chat_model VARCHAR(64);

COMMENT ON COLUMN memory_turns.chat_model IS '该轮对话所用的对话 model（ChatRequest.model）；后台压缩按此取，NULL 回退 memory.judge.model 默认';
COMMENT ON COLUMN memory_project_entries.chat_model IS '条目沿用源 turn 的对话 model；后台条目级压缩按此取，NULL 回退默认';

INSERT INTO system_settings (setting_key, setting_value, description) VALUES
    ('memory.judge.model', 'doubao-seed-2.0-code',
     '记忆管线 LLM 默认 model（路由/蒸馏/生成/压缩/冲突/召回标签）；请求域被对话所选 model 覆盖，后台域读源 chat_model，NULL/混合回退本值')
ON CONFLICT (setting_key) DO NOTHING;
