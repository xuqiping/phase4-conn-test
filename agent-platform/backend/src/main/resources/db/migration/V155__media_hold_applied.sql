-- ============================================================
-- V155: 媒体任务预扣标志（7x 未解决#2：提交即预扣预估积分，完工按实多退少补）
--   media_gen_tasks.hold_applied：提交时预扣是否已发生（个人钱包 VIDEO-HOLD/IMAGE-HOLD 流水
--   或组池 chargeGroup 预扣）。worker 结算据此分支：
--     TRUE  → settleMediaSuccess（差额补扣/退差）；失败 → refundMediaHold 全额退预扣
--     FALSE → 存量任务/未预扣（估价 0/计费开关关）走原「完工全量扣」路径
--   不加列则无法区分「升级前就在途的存量任务（未预扣）」与「新预扣任务」，结算会错退钱。
--
-- 回滚（rollback）：ALTER TABLE media_gen_tasks DROP COLUMN IF EXISTS hold_applied;
-- ============================================================

ALTER TABLE media_gen_tasks ADD COLUMN hold_applied BOOLEAN NOT NULL DEFAULT FALSE;
COMMENT ON COLUMN media_gen_tasks.hold_applied IS '提交期预估预扣是否已发生（7x）；TRUE=worker 按差额结算/失败退预扣，FALSE=原全量结算';
