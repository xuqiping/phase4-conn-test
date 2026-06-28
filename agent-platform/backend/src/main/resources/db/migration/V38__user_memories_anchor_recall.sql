-- V38：个人记忆召回锚点（anchor）—— LLM_KEY 语义两阶段召回的粗筛主通道。
-- 详见 速查表/09-个人记忆与冲突解决-优化升级进度.md §2.2、§三。
--
-- 现有 user_memories.embedding 是 value 向量（embed memory_value，用于块聚类），
-- 对专名/短值漂移严重。新增 anchor_embedding = embed(block_label + key_zh + key + entities)，
-- 「标签+词袋」语义更稳，召回率↑。与 value 向量并列，互不替代。
--
-- ⚠️ user_memories 无 deleted 列（不走 BaseEntity 软删）——本迁移无回填 UPDATE，
--    数据回填由 Java 侧 backfill-entities 端点跑（迁移不应调外部 LLM）。

-- ① 召回锚点向量（语义粗筛主通道）
ALTER TABLE user_memories ADD COLUMN anchor_embedding halfvec(2048);

-- ② 锚点词法 token（BM25 通道，jieba 分词后空格串 → tsvector）
ALTER TABLE user_memories ADD COLUMN anchor_tokens text;
ALTER TABLE user_memories ADD COLUMN anchor_tokens_tsv tsvector
  GENERATED ALWAYS AS (to_tsvector('simple', coalesce(anchor_tokens, ''))) STORED;

-- ③ HNSW ANN 索引（halfvec 余弦）——百万 key scale 必需
CREATE INDEX idx_user_memories_anchor_hnsw
  ON user_memories USING hnsw (anchor_embedding halfvec_cosine_ops);

-- ④ BM25 GIN 索引
CREATE INDEX idx_user_memories_anchor_tsv ON user_memories USING gin (anchor_tokens_tsv);
