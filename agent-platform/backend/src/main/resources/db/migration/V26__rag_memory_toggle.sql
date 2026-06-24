-- =====================================================================
-- V26：记忆模式开关（RAG+记忆+缓存 opt-in，4 层优先级）
-- 依据：记忆模式计划 / 用户决策（默认关，session>agent/workflow>global）
-- =====================================================================

-- 会话级开关（null = 继承 agent/workflow/global）
ALTER TABLE chat_sessions ADD COLUMN rag_enabled BOOLEAN;

-- 工作流级开关（null = 继承 global）
ALTER TABLE workflows ADD COLUMN rag_enabled BOOLEAN;

-- 全局总开关（默认 false = opt-in）
INSERT INTO system_settings (setting_key, setting_value, description)
VALUES ('rag.memory.enabled', 'false', 'RAG/记忆模式总开关（false=opt-in，关闭则纯裸聊天/Agent/工作流）')
ON CONFLICT (setting_key) DO NOTHING;
