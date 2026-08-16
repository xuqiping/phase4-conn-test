-- 5x #4：memory_project_entries 加 direction（INPUT/OUTPUT/BOTH），项目总结按方向取数落列。
-- 背景：V80 给 memory_summaries 加了 direction（个人通道已支持方向总结），但项目通道
--   summarizeProjectScope→writeProjectSummaryAndCoverage 硬编码 "BOTH"——根因是条目表无方向。
-- 回填推导（与写入侧 MemoryGenerationService#buildRoutingInput 口径一致）：
--   source_turn_id → OUTPUT turn 且 source_turn_input_id 非空 → BOTH（entry 覆盖整轮双侧面）
--   source_turn_id → OUTPUT turn 且 input 空 → OUTPUT（该轮只有输出侧被蒸馏）
--   source_turn_id → INPUT turn（单侧轮回退挂 INPUT）→ INPUT
--   source_turn_id IS NULL（FILE 条目/文件名硬规则）→ BOTH（文件无对话方向）
-- 幂等：ADD COLUMN IF NOT EXISTS + 约束存在性守卫 + 回填按 direction='BOTH' 收敛（重复执行零副作用）。
ALTER TABLE memory_project_entries ADD COLUMN IF NOT EXISTS direction VARCHAR(10) NOT NULL DEFAULT 'BOTH';

DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'chk_memory_project_entry_direction') THEN
        ALTER TABLE memory_project_entries ADD CONSTRAINT chk_memory_project_entry_direction
            CHECK (direction IN ('INPUT', 'OUTPUT', 'BOTH'));
    END IF;
END $$;

COMMENT ON COLUMN memory_project_entries.direction IS
    '条目方向（5x #4）：TEXT=蒸馏源轮次侧面（双侧 BOTH/仅输出 OUTPUT/仅输入 INPUT），FILE 恒 BOTH；项目总结按 direction 过滤取数';

-- 存量回填：按 source turn 推导（FILE 行 source_turn_id NULL 不命中 JOIN，保持默认 BOTH）
UPDATE memory_project_entries e
SET direction = CASE
        WHEN t.direction = 'INPUT' THEN 'INPUT'
        WHEN e.source_turn_input_id IS NOT NULL THEN 'BOTH'
        ELSE 'OUTPUT'
    END,
    updated_at = NOW()
FROM memory_turns t
WHERE t.id = e.source_turn_id
  AND e.direction = 'BOTH';
