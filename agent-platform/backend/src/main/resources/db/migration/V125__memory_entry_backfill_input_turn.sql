-- 5x #2 收尾：存量 memory_project_entries 回填 source_turn_input_id。
-- 背景：V100 加列起，写入侧已配对记 INPUT turn id（MemoryGenerationService#buildRoutingInput），
--   但 V100 只 ALTER 无回填——存量行 source_turn_input_id=NULL，流水账 INPUT 卡片仍看不到
--   「收录于:项目X」。本迁移一次性补齐（本库实测可回填 4 行）。
-- 配对口径（与写入侧一致）：entry.source_turn_id → OUTPUT turn → 同 user+session、方向 INPUT、
--   created_at 落在 OUTPUT 前 5 秒窗口内的最近一条 INPUT turn（processTurn 两次 insert 间隔毫秒级，
--   5s 已是千倍冗余；跨轮 INPUT 间隔远大于此，不会错配）。
-- 不动三类行：source_turn_id 本就挂 INPUT（单侧轮，UNION 第一支已可反查）/ 无匹配 INPUT
--   （prefilter 跳过输入侧，物理上不存在配对）/ source_turn_id IS NULL（FILE 条目走 file_id 链路）。
WITH pairs AS (
    SELECT DISTINCT ON (src.id) src.id AS out_turn_id, t.id AS in_turn_id
    FROM memory_turns src
    JOIN memory_turns t
      ON t.user_id = src.user_id
     AND t.session_id = src.session_id
     AND t.direction = 'INPUT'
     AND t.deleted = 0
     AND t.created_at <= src.created_at
     AND t.created_at >= src.created_at - interval '5 seconds'
    WHERE src.direction = 'OUTPUT'
      AND src.deleted = 0
)
UPDATE memory_project_entries e
SET source_turn_input_id = pairs.in_turn_id,
    updated_at = NOW()
FROM pairs
WHERE e.deleted = 0
  AND e.source_turn_input_id IS NULL
  AND e.source_turn_id = pairs.out_turn_id;

COMMENT ON COLUMN memory_project_entries.source_turn_input_id IS
    '配对 INPUT turn 软链（5x #2）；entry 覆盖整轮，source_turn_id 挂 OUTPUT、本列挂同轮 INPUT，两 turn 均可反查收录；V125 已回填存量';
