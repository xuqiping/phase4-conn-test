-- V81: memory_notifications.type CHECK 补 USER_GRANT_REQUEST / USER_GRANT_RESULT。
-- 背景：记忆二期 P1（项目↔个人授权）的 notifyProjectManagers / notifyGrantee 用
--   USER_GRANT_REQUEST（个人申请→通知项目 owner/admin 审批）
--   USER_GRANT_RESULT（项目主动授权 / 审批结果 / 撤销 → 通知被授权人）
-- 但 V47 建表、V77 重建的 CHECK 仅含 5 类，未含这两类 → 插入通知违反 CHECK →
-- grant 仍写库（先于通知提交），但接口返 409「数据已存在」误报，前端弹错误 toast。
-- 修复：重建 CHECK 把这两类纳入（与 MemoryProjectUserGrantService 常量对齐）。

ALTER TABLE memory_notifications DROP CONSTRAINT IF EXISTS memory_notifications_type_check;
ALTER TABLE memory_notifications
    ADD CONSTRAINT memory_notifications_type_check
    CHECK (type IN ('SUMMARY_AFFECTED_BY_RECALL',
                    'PROJECT_DELETED_AFFECTED',
                    'LINK_REQUEST',
                    'LINK_RESULT',
                    'TAG_NEEDS_REVIEW',
                    'USER_GRANT_REQUEST',
                    'USER_GRANT_RESULT'));
COMMENT ON COLUMN memory_notifications.type IS '通知类型：SUMMARY_AFFECTED_BY_RECALL/PROJECT_DELETED_AFFECTED/LINK_REQUEST/LINK_RESULT/TAG_NEEDS_REVIEW/USER_GRANT_REQUEST（个人申请召回待审批）/USER_GRANT_RESULT（授权结果/撤销）';
