-- V32: 记忆中文标签列 memory_key_zh。
-- memory_key 是英文蛇形短键(child_name)，丢失了"女儿"这类中文角色/称谓桥接词 →
--   query「带女儿去玩」token"女儿"撞不上英文 key、也撞不上 value(可能只是名字"啊闪")，关键词召回必漏。
-- memory_key_zh 存中文主标签(如"女儿")：① 前端「名称」列显示 ② findByKeyword 关键词召回锚点。
-- 写时复用 extract LLM 顺带抽(0 额外调用)。可空：老行 NULL = 无中文标签，仅参与既有 entities/value 召回。
-- 配套 V31 entities 扩成"召回词袋"(标签+同义变体+value专名)，见 MemoryConflictJudge 抽取 prompt。
ALTER TABLE user_memories ADD COLUMN memory_key_zh TEXT;
COMMENT ON COLUMN user_memories.memory_key_zh IS 'memory_key 中文标签（如"女儿"），前端显示 + 关键词召回锚点，写时 LLM 抽';
