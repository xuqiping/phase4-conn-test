-- V151：17c 修复——项目组划拨/回收第二次操作撞 uq_ledger_ref 409。
-- 根因：uq_ledger_ref(ref_type,ref_id,type) 全表唯一，而个人侧划拨/回收流水 refId=groupId 恒定
--       → 同组第二次 GROUP_ALLOCATE/GROUP_RECLAIM 必撞（GROUP, groupId, type）。
-- 该约束本意是幂等锚（支付一单一行/媒体一任务一行，ref_id 天然唯一）；
-- 划拨/回收是「一次点击一笔新流水」，无天然幂等键，组 id 不该当锚。
-- 修法：约束改部分唯一索引——排除 ref_type='GROUP'，其余（PAYMENT/CHAT/VIDEO/IMAGE/ADMIN）锚定语义不变。
-- 存量：GROUP 行此前每（组,type）至多一行（第二次都被拒了），无重复数据要清。

ALTER TABLE points_ledger DROP CONSTRAINT IF EXISTS uq_ledger_ref;

CREATE UNIQUE INDEX uq_ledger_ref ON points_ledger (ref_type, ref_id, type)
    WHERE ref_type <> 'GROUP';
