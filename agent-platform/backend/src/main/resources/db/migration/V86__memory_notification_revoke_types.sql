-- V86: memory_notifications.type CHECK 补 LINK_REVOKE_REQUEST / LINK_REVOKE_RESULT。
-- 背景：记忆三期（项目↔项目授权 link 非对称撤销）的
--   notifyParentManagersRevokeRequest 用 LINK_REVOKE_REQUEST（child 申请撤销→通知 parent owner/admin 审批）
--   notifyRevokeRequester 用 LINK_REVOKE_RESULT（parent 审批撤销结果→通知 child 申请人）
-- 但 V47 建表、V77 重建、V81 补 USER_GRANT 两类后的 CHECK 仍仅含 7 类，未含这两类 →
-- 插入通知违反 CHECK → link 状态 UPDATE 仍写库（先于通知提交），但接口返 409「数据已存在」误报
-- （与 V81 同坑：grant/状态先于通知提交）。修复：重建 CHECK 把这两类纳入
-- （与 MemoryProjectLinkService 常量 NOTIFY_TYPE_LINK_REVOKE_REQUEST / NOTIFY_TYPE_LINK_REVOKE_RESULT 对齐）。

ALTER TABLE memory_notifications DROP CONSTRAINT IF EXISTS memory_notifications_type_check;
ALTER TABLE memory_notifications
    ADD CONSTRAINT memory_notifications_type_check
    CHECK (type IN ('SUMMARY_AFFECTED_BY_RECALL',
                    'PROJECT_DELETED_AFFECTED',
                    'LINK_REQUEST',
                    'LINK_RESULT',
                    'TAG_NEEDS_REVIEW',
                    'USER_GRANT_REQUEST',
                    'USER_GRANT_RESULT',
                    'LINK_REVOKE_REQUEST',
                    'LINK_REVOKE_RESULT'));
COMMENT ON COLUMN memory_notifications.type IS '通知类型：SUMMARY_AFFECTED_BY_RECALL/PROJECT_DELETED_AFFECTED/LINK_REQUEST/LINK_RESULT/TAG_NEEDS_REVIEW/USER_GRANT_REQUEST（个人申请召回待审批）/USER_GRANT_RESULT（授权结果/撤销）/LINK_REVOKE_REQUEST（child 申请撤销待 parent 审批）/LINK_REVOKE_RESULT（parent 撤销审批结果）';
