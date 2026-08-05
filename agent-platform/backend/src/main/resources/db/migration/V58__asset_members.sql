-- ============================================================
-- V58: 项目资产库 · 成员授权表 + asset:write 权限 seed（asset_project_members）
-- 功能：项目级三角色授权（plan §S3 / FR-002，设计方案 §七）
-- 设计要点：
--   1. 项目数据权限（第二层）：asset_project_members 记录非 owner 的项目成员（viewer/editor）。
--      owner 不落本表（owner_id 即所有者，在 asset_projects）；admin 平台旁路全量。
--   2. 双层授权：被授权用户**同样需要 asset:write 平台权限**（第一层），两层都过才可见项目（设计方案 §七 7.1）。
--   3. 离开授权即失访（L1）：member 移除后项目从其列表消失；其在画布中已引用的资产快照不受影响（引用的是版本快照 file_id）。
--   4. 继承 BaseEntity；UNIQUE(project_id,user_id) WHERE deleted=0 数据库兜底防重复授权（plan 坑点预判），
--      部分唯一索引以便"移除后可重新授权"。
-- 权限策略 = gated：asset:write 仅 admin 默认有，普通 user 由 admin 按需授（同 V55 canvas:write / V54 media:gen）。
-- ============================================================

-- 1. 项目成员表
CREATE TABLE asset_project_members (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by       BIGINT,
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    deleted          INTEGER                     NOT NULL DEFAULT 0,
    version          INTEGER                     NOT NULL DEFAULT 0,
    project_id       BIGINT                      NOT NULL,                       -- 所属项目（FK asset_projects）
    user_id          BIGINT                      NOT NULL,                       -- 被授权用户
    role             VARCHAR(8)                  NOT NULL,                       -- 项目角色：VIEWER(只读引用)/EDITOR(可写)；owner 不落表
    granted_by       BIGINT,                                                      -- 授权人（= 操作 owner，审计用）
    CONSTRAINT fk_member_project FOREIGN KEY (project_id)
        REFERENCES asset_projects(id) ON DELETE CASCADE,
    CONSTRAINT chk_member_role CHECK (role IN ('VIEWER','EDITOR'))
);

-- 「共享给我」列表：按 user_id 查被授权项目（设计方案 §十）
CREATE INDEX idx_asset_member_user ON asset_project_members(user_id) WHERE deleted = 0;
CREATE INDEX idx_asset_member_project ON asset_project_members(project_id) WHERE deleted = 0;
-- 数据库兜底防重复授权（部分唯一索引：移除后可重新授权，plan 坑点预判）
CREATE UNIQUE INDEX uk_asset_member_project_user ON asset_project_members(project_id, user_id) WHERE deleted = 0;

COMMENT ON TABLE  asset_project_members            IS '项目资产库·成员授权（第二层数据权限）。owner 不落表，admin 平台旁路。';
COMMENT ON COLUMN asset_project_members.role       IS '项目角色：VIEWER(浏览/搜索/下载/只读引用)/EDITOR(可写：上传/编辑/入库/定稿/归档/维护词汇)';
COMMENT ON COLUMN asset_project_members.granted_by  IS '授权人 userId（审计用）；转让/移除/改角色打审计日志';
COMMENT ON COLUMN asset_project_members.deleted     IS '软删标记（@TableLogic）；移除成员=软删，可重新授权';

-- 2. 权限 seed（gated：仅 admin 默认有 asset:write）
INSERT INTO permissions (name, code, resource, action) VALUES
    ('项目资产库', 'asset:write', 'asset', 'write')
ON CONFLICT DO NOTHING;

-- 2.1 系统管理员：默认有（可创作项目 + 可授权/撤销）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin' AND p.code = 'asset:write'
ON CONFLICT DO NOTHING;

-- 注：普通 user / agent_admin 默认不给 asset:write（admin 按需授，同 V54 media:gen / V55 canvas:write）。

-- ============================================================
-- 回滚（rollback）：
-- DROP TABLE IF EXISTS asset_project_members;
-- DELETE FROM role_permissions WHERE permission_id IN (SELECT id FROM permissions WHERE code='asset:write');
-- DELETE FROM permissions WHERE code='asset:write';
-- ============================================================
