-- V79: 记忆二期 P1 · 项目↔个人授权（memory_project_user_grants）。
-- 语义：把「项目条目的召回读权」授权给某个个人。与项目→项目授权（V73 memory_project_links）并列：
--   · 双向发起：项目 owner/admin 主动授权个人（initiated_by=PROJECT，立即 ACTIVE）；
--                 个人申请召回某项目（initiated_by=USER，落 PENDING 待项目 owner/admin 审批）。
--   · 只读召回：ACTIVE 授权让被授权人能在「召回范围」勾选该项目并召回其条目摘要；不写回、不进该项目总结生成。
--   · 生命周期：PENDING → ACTIVE / REJECTED；ACTIVE → REVOKED（双方可撤）；REJECTED 30 天内同对不可重提；
--     REVOKED / REJECTED 超期再发起 = 同行复活 PENDING（防刷键 uk 部分唯一，按 created_at 判）。
--   · 撤销=status='REVOKED'（审计留痕行不删）；个人取消自己 PENDING 申请=软删（未生效无审计必要）。
--
-- 关键设计（镜像 V73）：
--   ① 防刷键 (project_id, user_id) 落部分 UNIQUE 行不删；
--   ② 状态翻转走条件 UPDATE（WHERE status=:expected），并发打不穿（影响行数=0 → 409）；
--   ③ 召回取数实时算 ACTIVE 集 → revoke 即时断召回（无缓存）。

CREATE TABLE memory_project_user_grants (
    id            BIGINT                   GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id    BIGINT                   NOT NULL REFERENCES projects(id) ON DELETE CASCADE,  -- 被授权的项目（条目来源），随项目死
    user_id       BIGINT                   NOT NULL REFERENCES users(id) ON DELETE CASCADE,     -- 被授权的个人（召回受益人）
    initiated_by  VARCHAR(10)              NOT NULL
                  CHECK (initiated_by IN ('PROJECT','USER')),                                  -- 发起方：PROJECT=项目主动授权 / USER=个人申请
    granted_by    BIGINT,                                                                          -- 发起人（PROJECT 侧=项目 owner/admin；USER 侧=申请人自己）
    approved_by   BIGINT,                                                                          -- 审批人（USER 发起时=项目 owner/admin）
    status        VARCHAR(20)              NOT NULL DEFAULT 'PENDING'
                  CHECK (status IN ('PENDING','ACTIVE','REJECTED','REVOKED')),
    approved_at   TIMESTAMP WITH TIME ZONE,                                                      -- 审批时间（通过/拒绝）
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),  -- 发起时间（REJECTED 30 天防刷判据）
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted       INT                      NOT NULL DEFAULT 0,
    version       INT                      NOT NULL DEFAULT 0
);

-- 防刷键：同 (project_id, user_id) 仅一条活行（复活语义保证；软删行不挡）
CREATE UNIQUE INDEX uk_memory_project_user_grants_pair ON memory_project_user_grants(project_id, user_id) WHERE deleted = 0;
-- 召回取数主查询：被授权个人查 ACTIVE 授权项目集
CREATE INDEX idx_mpug_user_status  ON memory_project_user_grants(user_id, status)    WHERE deleted = 0;
-- 项目侧列表/审批：项目 owner/admin 查收到的申请/发出的授权
CREATE INDEX idx_mpug_proj_status  ON memory_project_user_grants(project_id, status) WHERE deleted = 0;

COMMENT ON TABLE  memory_project_user_grants IS '项目↔个人授权（二期 P1）：把项目条目召回读权授权给个人。双向发起 PENDING→ACTIVE/REJECTED；双方可撤 ACTIVE→REVOKED；只读召回（不写回）';
COMMENT ON COLUMN memory_project_user_grants.project_id   IS '被授权的项目（其条目可被 user_id 召回摘要）';
COMMENT ON COLUMN memory_project_user_grants.user_id      IS '被授权的个人（召回受益人；可勾选本项目到召回范围）';
COMMENT ON COLUMN memory_project_user_grants.initiated_by IS 'PROJECT=项目 owner/admin 主动授权（立即 ACTIVE）；USER=个人申请（PENDING 待审批）';
COMMENT ON COLUMN memory_project_user_grants.status       IS 'PENDING=待项目 owner/admin 审批；ACTIVE=生效；REJECTED=被拒（30 天防刷，按 created_at 判）；REVOKED=已撤销（审计留痕行不删）';
COMMENT ON COLUMN memory_project_user_grants.created_at   IS '发起时间；REJECTED 后 30 天内同对重复发起 → 409（复活时重置本字段）';
