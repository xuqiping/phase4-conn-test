-- ============================================================
-- V123: 2x 第三轮问题修复 C3 —— media_gen_tasks 并发对账部分索引
-- 背景（15x_并发.md 三问落地）：每用户 video/image 生成任务并发上限
--   （system_settings: media.concurrent.video 默认 2 / media.concurrent.image 默认 3，0=不限制）。
--   闸门用 Redis INCR 计数（inflight:media:{kind}:u:{userId}），worker 终态 release。
-- 本索引：MediaInflightGateService.reconcile 每小时对账
--   「Redis 在途计数 vs DB 未终态任务数（GROUP BY user_id, task_type）」，
--   漂移 > 2 → WARN（Redis 少计=超卖风险；多计=泄漏，TTL 30min 自愈）。
-- 部分索引（只覆盖 PENDING/RUNNING 活跃行）：任务表 append-only、终态行占绝对多数，
--   活跃行稀疏 → 索引极小且写入热路径（submit/claim/mark）均命中。
-- ============================================================

CREATE INDEX idx_mgen_user_type_active ON media_gen_tasks(user_id, task_type, status)
    WHERE status IN ('PENDING', 'RUNNING');

COMMENT ON INDEX idx_mgen_user_type_active IS
    'C3 并发对账：每用户每类型未终态任务数（reconcile 与 Redis inflight:media:* 比对用）';

-- ============================================================
-- 回滚（rollback）：
-- DROP INDEX IF EXISTS idx_mgen_user_type_active;
-- （纯新增索引，无数据变更；闸门上限键 media.concurrent.* 缺省走代码默认 2/3）
-- ============================================================
