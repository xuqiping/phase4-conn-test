-- ============================================================
-- V138: 项目组共享化 · 产出可见性 + 邀请同意 + 公共池招募（17x 未解决四项）
-- 功能：
--   1. project_groups 加「成员产出可见性」两列（17x#2）：
--      member_output_visibility OWN(默认,成员仅看自己)/ALL(成员看全组)；
--      module_visibility_overrides JSONB 稀疏按模块覆盖（如 {"CHAT":"ALL"}，缺省回落上行）。
--   2. project_group_invites 邀请表（17x#3）：加成员从「直接写入」改「邀请→被邀请人同意」
--      状态机 PENDING→ACCEPTED/DECLINED（组长可 CANCELED）；quota 快照随邀请到接受落成员行。
--   3. 公共池招募（17x#4）：project_groups 加 public_pool 三列 + project_group_join_requests
--      申请表（PENDING→APPROVED/REJECTED，撤池级联 PENDING→REVOKED，镜像资产公众池先例）。
--   4. memory_notifications.type CHECK 重建补 4 类（V81 先例）：
--      GROUP_INVITE/GROUP_INVITE_RESULT/GROUP_JOIN_REQUEST/GROUP_JOIN_RESULT。
-- 设计要点：
--   - 邀请/申请同组同人 PENDING 唯一（部分唯一索引），复活走条件 UPDATE（grant 状态机先例）。
--   - 产出文件可见性判定在服务层集中（ProjectGroupVisibilityService），本迁移只落配置列。
-- 回滚：新表 drop；加列 drop；CHECK 回滚到 V81 口径（已有 GROUP_* 通知须先清）。
-- ============================================================

-- 1. 组可见性 + 公共池列
ALTER TABLE project_groups ADD COLUMN member_output_visibility VARCHAR(20) NOT NULL DEFAULT 'OWN';
ALTER TABLE project_groups ADD COLUMN module_visibility_overrides JSONB;
ALTER TABLE project_groups ADD COLUMN public_pool BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE project_groups ADD COLUMN public_published_by BIGINT;
ALTER TABLE project_groups ADD COLUMN public_published_at TIMESTAMPTZ;
ALTER TABLE project_groups ADD CONSTRAINT ck_pg_member_vis
    CHECK (member_output_visibility IN ('OWN','ALL'));
COMMENT ON COLUMN project_groups.member_output_visibility IS '成员产出可见性（17x#2）：OWN=成员仅看自己（默认，V133 口径）；ALL=成员互见全组。组长/admin 恒全量';
COMMENT ON COLUMN project_groups.module_visibility_overrides IS '按模块稀疏覆盖 JSONB（key=CHAT/EMBED/RERANK/IMAGE/VIDEO，value=OWN/ALL）；模块缺省回落 member_output_visibility';
COMMENT ON COLUMN project_groups.public_pool IS '公共池招募开关（17x#4）：true=全平台可见可申请加入；组长随时可撤出';
COMMENT ON COLUMN project_groups.public_published_by IS '推入公共池操作人（留痕）';
COMMENT ON COLUMN project_groups.public_published_at IS '推入公共池时间';

-- 2. 邀请表（17x#3）
CREATE TABLE project_group_invites (
    id                  BIGINT                GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    deleted             INTEGER               NOT NULL DEFAULT 0,
    version             INTEGER               NOT NULL DEFAULT 0,
    group_id            BIGINT                NOT NULL,
    inviter_user_id     BIGINT                NOT NULL,               -- 发起人（组长/admin 代管）
    invitee_user_id     BIGINT                NOT NULL,               -- 被邀请人（决策方）
    quota_limit_points  NUMERIC(14,2),                               -- 接受后落成员行的限额快照（NULL=不限）
    status              VARCHAR(16)           NOT NULL DEFAULT 'PENDING',
    decided_at          TIMESTAMPTZ,
    CONSTRAINT ck_pgi_status CHECK (status IN ('PENDING','ACCEPTED','DECLINED','CANCELED')),
    CONSTRAINT ck_pgi_quota_nonneg CHECK (quota_limit_points IS NULL OR quota_limit_points >= 0),
    CONSTRAINT fk_pgi_group FOREIGN KEY (group_id) REFERENCES project_groups(id) ON DELETE CASCADE
);
-- 同组同人仅一条 PENDING（软删行不挡）
CREATE UNIQUE INDEX uk_pgi_pending ON project_group_invites(group_id, invitee_user_id)
    WHERE status = 'PENDING' AND deleted = 0;
CREATE INDEX idx_pgi_invitee ON project_group_invites(invitee_user_id, status) WHERE deleted = 0;
COMMENT ON TABLE project_group_invites IS '组邀请（17x#3）：加成员改邀请制，被邀请人 ACCEPTED 才落成员行；DECLINED 可再邀请（同行复活 PENDING）';

-- 3. 公共池申请表（17x#4，镜像 asset_public_access_requests 状态机）
CREATE TABLE project_group_join_requests (
    id                  BIGINT                GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by          BIGINT,
    created_at          TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    updated_by          BIGINT,
    updated_at          TIMESTAMPTZ           NOT NULL DEFAULT NOW(),
    deleted             INTEGER               NOT NULL DEFAULT 0,
    version             INTEGER               NOT NULL DEFAULT 0,
    group_id            BIGINT                NOT NULL,
    user_id             BIGINT                NOT NULL,               -- 申请人（本人发起）
    message             VARCHAR(200),                                 -- 申请留言（可选）
    status              VARCHAR(16)           NOT NULL DEFAULT 'PENDING',
    decided_by          BIGINT,
    decided_at          TIMESTAMPTZ,
    CONSTRAINT ck_pgjr_status CHECK (status IN ('PENDING','APPROVED','REJECTED','REVOKED')),
    CONSTRAINT fk_pgjr_group FOREIGN KEY (group_id) REFERENCES project_groups(id) ON DELETE CASCADE
);
CREATE UNIQUE INDEX uk_pgjr_pending ON project_group_join_requests(group_id, user_id)
    WHERE status = 'PENDING' AND deleted = 0;
CREATE INDEX idx_pgjr_user ON project_group_join_requests(user_id, status) WHERE deleted = 0;
COMMENT ON TABLE project_group_join_requests IS '公共池入组申请（17x#4）：APPROVED 落成员行（quota NULL）；REJECTED 30 天防刷窗口在服务层（grant 先例）；撤池级联 PENDING→REVOKED';

-- 4. 通知类型扩展（V81 先例：PG 不支持 ALTER CONSTRAINT 加值，DROP 再 ADD）
--    注意：存量库有 LINK_REVOKE_REQUEST/LINK_REVOKE_RESULT，必须保留在 CHECK 列表中。
ALTER TABLE memory_notifications DROP CONSTRAINT IF EXISTS memory_notifications_type_check;
ALTER TABLE memory_notifications
    ADD CONSTRAINT memory_notifications_type_check
    CHECK (type IN ('SUMMARY_AFFECTED_BY_RECALL',
                    'PROJECT_DELETED_AFFECTED',
                    'LINK_REQUEST',
                    'LINK_RESULT',
                    'LINK_REVOKE_REQUEST',
                    'LINK_REVOKE_RESULT',
                    'TAG_NEEDS_REVIEW',
                    'USER_GRANT_REQUEST',
                    'USER_GRANT_RESULT',
                    'GROUP_INVITE',
                    'GROUP_INVITE_RESULT',
                    'GROUP_JOIN_REQUEST',
                    'GROUP_JOIN_RESULT'));
COMMENT ON COLUMN memory_notifications.type IS '通知类型：SUMMARY_AFFECTED_BY_RECALL/PROJECT_DELETED_AFFECTED/LINK_REQUEST/LINK_RESULT/LINK_REVOKE_REQUEST/LINK_REVOKE_RESULT/TAG_NEEDS_REVIEW/USER_GRANT_REQUEST/USER_GRANT_RESULT/GROUP_INVITE（组邀请待同意）/GROUP_INVITE_RESULT（邀请结果）/GROUP_JOIN_REQUEST（入组申请待审批）/GROUP_JOIN_RESULT（申请结果）';

-- ============================================================
-- 回滚（rollback）：
-- ALTER TABLE memory_notifications DROP CONSTRAINT IF EXISTS memory_notifications_type_check;
-- ALTER TABLE memory_notifications ADD CONSTRAINT memory_notifications_type_check
--     CHECK (type IN ('SUMMARY_AFFECTED_BY_RECALL','PROJECT_DELETED_AFFECTED','LINK_REQUEST',
--                     'LINK_RESULT','TAG_NEEDS_REVIEW','USER_GRANT_REQUEST','USER_GRANT_RESULT'));
--   -- ⚠ 已有 GROUP_* 通知行须先 DELETE
-- DROP TABLE IF EXISTS project_group_join_requests;
-- DROP TABLE IF EXISTS project_group_invites;
-- ALTER TABLE project_groups DROP COLUMN IF EXISTS public_published_at;
-- ALTER TABLE project_groups DROP COLUMN IF EXISTS public_published_by;
-- ALTER TABLE project_groups DROP COLUMN IF EXISTS public_pool;
-- ALTER TABLE project_groups DROP COLUMN IF EXISTS module_visibility_overrides;
-- ALTER TABLE project_groups DROP COLUMN IF EXISTS member_output_visibility;
-- ============================================================
