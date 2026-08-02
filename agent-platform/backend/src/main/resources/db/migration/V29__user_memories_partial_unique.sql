-- V29: user_memories 部分唯一索引，防 processMemory 全异步并发竞态重复插入
-- clean 记忆(conflict_id IS NULL)同 user 同 memory_key 唯一；
-- FLAGGED(conflict_id 非空)可共存（冲突双版本待手动 resolve）。
-- 兼顾 V28（删旧 unique 供冲突共存）与防重复：只约束 clean，不约束 FLAGGED。
-- 先清现存 clean 重复（留最小 id），再加索引（CREATE UNIQUE 要求无现存重复）。
DELETE FROM user_memories a USING user_memories b
WHERE a.id > b.id
  AND a.user_id = b.user_id
  AND a.memory_key = b.memory_key
  AND a.conflict_id IS NULL
  AND b.conflict_id IS NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_memories_user_key_clean
    ON user_memories (user_id, memory_key)
    WHERE conflict_id IS NULL;
