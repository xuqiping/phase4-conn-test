-- V43: memory_key_meta —— per-key 时序事实标记(M2 时间线记忆)。
-- 背景:M2 决策(2026-07-18):某 memory_key 是否「时序事实」(住址=是,孩子数量=否)由用户在
-- 首次该 key 走到 pending 冲突时回答 LLM 询问决定,答案落本表 per-key 持久化;后续该 key 的
-- KEEP_BOTH merge 按本表 is_temporal 标走(时序=各段带日期前缀按序拼 ;,非时序=中文逗号 join 现状)。
-- 直到用户在 panel 显式改标,否则标固定复用。
-- 与 user_memories 正交:本表是 key 级元数据,独立于行级记忆;不参与日常召回,仅 merge/resolve 读。
-- 不走 BaseEntity 软删(同 user_memories 域约定);无向量列。
CREATE TABLE memory_key_meta (
    id            BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id),
    memory_key    VARCHAR(200) NOT NULL,
    is_temporal   BOOLEAN NOT NULL,
    source        VARCHAR(20) NOT NULL DEFAULT 'LLM_ASK',  -- LLM_ASK=首次询问用户答 / USER_OVERRIDE=panel 手改
    created_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);

-- 每用户每 key 一条标。COALESCE 解 PG NULL distinct(此处列均 NOT NULL,直接唯一即可)。
CREATE UNIQUE INDEX uk_memory_key_meta_user_key ON memory_key_meta(user_id, memory_key);
CREATE INDEX idx_memory_key_meta_user ON memory_key_meta(user_id);

COMMENT ON TABLE memory_key_meta IS 'M2:per-user per-key 时序事实标记。首次 pending 冲突时 LLM 问用户→答案落此,KEEP_BOTH merge 按此标决定走时间线(带日期段)还是中文逗号 join。panel 可改标(USER_OVERRIDE)。';
COMMENT ON COLUMN memory_key_meta.is_temporal IS 'true=时序事实(value 各段带 ISO 日期前缀按序拼 ;),false=非时序(中文逗号 join 现状)。';
COMMENT ON COLUMN memory_key_meta.source IS 'LLM_ASK=首次 pending 询问用户所答;USER_OVERRIDE=用户在 panel 显式修改。';
