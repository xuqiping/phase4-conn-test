-- V85: 记忆三期 · 项目↔项目授权 link 非对称撤销（child 主动撤销需 parent 审批 / parent 主动撤销即时+通知）。
--
-- 设计：加 revoke_requested_by/at 两列，status 维持 ACTIVE（不加新状态）。
--   ① 召回/读权门控全过滤 status='ACTIVE'，加列后门控零改动——审批前 link 仍 ACTIVE，parent 仍可读/召回；
--   ② child owner 主动撤销 → 置 revoke_requested_by/at（挂起待审批），status 不变，不 fire STALE（数据仍可读）；
--     parent owner/admin 审批通过 → status ACTIVE→REVOKED（此时 fire STALE）；拒绝 → 清列、status 留 ACTIVE；
--   ③ parent owner/admin 主动撤销 → 即时 ACTIVE→REVOKED + STALE + 通知 child（不需 child 审核）。
-- 单端点 DELETE /links/{id} 由后端按调用方身份裁决（child→挂起 / parent→即时）。

ALTER TABLE memory_project_links ADD COLUMN revoke_requested_by BIGINT REFERENCES users(id) ON DELETE SET NULL;
ALTER TABLE memory_project_links ADD COLUMN revoke_requested_at TIMESTAMP WITH TIME ZONE;

COMMENT ON COLUMN memory_project_links.revoke_requested_by IS 'child owner 主动撤销的申请人（非空=撤销申请挂起待 parent 审批；status 仍 ACTIVE）';
COMMENT ON COLUMN memory_project_links.revoke_requested_at IS '撤销申请发起时间';
