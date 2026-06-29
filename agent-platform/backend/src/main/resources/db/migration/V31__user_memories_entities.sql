-- V31: 记忆实体标签列（VECTOR_KEYWORD 检索模式用）。
-- 写时复用 extract LLM 顺带抽实体（0 额外调用）存 JSONB 数组，如 ["女儿","北京"]。
-- 召回时 query 分词 → ILIKE 命中 entities，专治向量漏的"实体桥接"类
--   （如 记忆"女儿3岁" key=child_age value=3岁，query"带女儿去玩" 靠 token"女儿"召回）。
-- 可空：老行 entities=NULL → 不参与关键词召回，仅向量（向后兼容，无 NOT NULL 陷阱）。
ALTER TABLE user_memories ADD COLUMN entities JSONB;
COMMENT ON COLUMN user_memories.entities IS '实体标签 JSONB 数组(如["女儿","北京"]),VECTOR_KEYWORD 召回用,写时 LLM 抽';
