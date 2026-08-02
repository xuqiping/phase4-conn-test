-- 记忆冲突解决（V27）允许同 (user_id, key) 多行共存：
-- FLAGGED 冲突两行、或 LLM 对不同事实给出相同 key 时，都需要同 key 多行。
-- V6 的 unique(user_id, memory_key) 是旧 upsert-by-key 设计，现已废弃（processMemory 改 insert+冲突判定）。
-- 不删则 flag/KEEP_NEW 在 key 碰撞时 unique 违例 → 500。
DROP INDEX IF EXISTS idx_user_memories_user_key;
