-- 二期 P3c：memory_summaries 增 direction 列（总结区分 输入/输出/综合）。
-- 用户反馈「总结还要区分 input 和 output，而不是统一在一条里面，因为有些输出并不一定是我想要的」。
-- INPUT=仅压输入侧 turn、OUTPUT=仅压输出侧、BOTH=综合（历史行默认 BOTH，不回填重压）。
-- 方向取数在 MemoryConsolidationService（PERSONAL 走 req.direction→findPersonalTurnsForConsolidation；
-- PROJECT 条目无方向，记 BOTH），写入由 MemoryConsolidationTxService 落列。

ALTER TABLE memory_summaries ADD COLUMN direction VARCHAR(10) NOT NULL DEFAULT 'BOTH';

ALTER TABLE memory_summaries ADD CONSTRAINT chk_memory_summary_direction
    CHECK (direction IN ('INPUT', 'OUTPUT', 'BOTH'));

CREATE INDEX idx_memory_summaries_direction ON memory_summaries(direction) WHERE deleted = 0;
