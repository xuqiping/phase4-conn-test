-- V51: 计划12 · 迭代 E · 总结 worker 任务锁列（总体设计 §3.4 + plan E「FOR UPDATE SKIP LOCKED + 幂等」）。
-- memory_consolidation_scopes 加 locked_until + last_run_at 两列：
--   locked_until —— 任务锁（worker 认领即置 now+LOCK_MINUTES，完成清 NULL；双节点 SKIP LOCKED 互斥）。
--   last_run_at  —— 上次成功跑时（幂等：同周期内 last_run_at >= 周期起点则跳过，防重复压缩 LLM 计费）。
-- 复用 consolidation_scopes 行作锁目标（不新建 job 表）：手动总结也 upsert 行（auto_enabled=false）走同一锁路径，
-- 手动与定时互斥不双跑。PERSONAL 行由 V47 trigger 默认建（auto_enabled=true）；PROJECT 行按需 upsert。
--
-- 坑规避：
--   ① LLM 压缩秒级阻塞+计费 → claim(短事务置锁) → process(事务外 LLM) → complete(短事务清锁+last_run_at)，
--      三段独立短事务，不持锁跨 LLM（同 IndexJobWorker/IndexJobTxService 范式）；
--   ② 已执行脚本不可改，本迁移只加列，不改 V47 既建结构。

ALTER TABLE memory_consolidation_scopes
    ADD COLUMN locked_until TIMESTAMP WITH TIME ZONE,
    ADD COLUMN last_run_at  TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN memory_consolidation_scopes.locked_until IS '总结任务锁；worker 认领置 now+LOCK_MINUTES，完成清 NULL。双节点 SELECT FOR UPDATE SKIP LOCKED 互斥';
COMMENT ON COLUMN memory_consolidation_scopes.last_run_at  IS '上次成功总结时刻；同周期内 last_run_at >= 周期起点则跳过（幂等，防重复压缩计费）';
