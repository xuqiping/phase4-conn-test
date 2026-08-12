-- 5x #2：memory_project_entries 加 source_turn_input_id。
-- 根因：buildRoutingInput 把 source_turn_id 优先挂 OUTPUT turn（MemoryGenerationService:155），
--   同轮 INPUT turn 天然无收录链 → findProjectIndexByTurnIds 反查 INPUT 恒空 → 流水账 INPUT 卡片
--   永不显「收录于:项目X」。entry 本由双侧 L1 合并蒸馏（覆盖整轮输入+输出），故加配对 INPUT 列。
-- 语义：source_turn_id=OUTPUT（保留，向后兼容）/ source_turn_input_id=INPUT（新，可空）。
--   查询 findProjectIndexByTurnIds UNION 两列 → INPUT/OUTPUT turn 均可反查到收录项目。
-- 兼容：老行 source_turn_input_id=NULL，查询 IN 对 NULL 安全（不匹配，等价旧 OUTPUT-only 行为）。
ALTER TABLE memory_project_entries ADD COLUMN source_turn_input_id BIGINT;

COMMENT ON COLUMN memory_project_entries.source_turn_input_id IS
    '配对 INPUT turn 软链（5x #2）；entry 覆盖整轮，source_turn_id 挂 OUTPUT、本列挂同轮 INPUT，两 turn 均可反查收录';

-- 反查主查询索引（findProjectIndexByTurnIds 按两列 IN 查）
CREATE INDEX idx_memory_project_entries_source_input ON memory_project_entries(source_turn_input_id)
    WHERE deleted = 0 AND source_turn_input_id IS NOT NULL;
