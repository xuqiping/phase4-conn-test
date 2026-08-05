-- ============================================================
-- V56: 项目资产库 · 项目表（asset_projects）
-- 功能：项目资产库（plan 项目资产库.plan.md §S1 / FR-001，设计方案 §九）
-- 设计要点：
--   1. 项目（Project）是资产的唯一命名空间与授权边界（设计方案 §二）。
--      owner_id = 项目所有者（唯一，不可空）；项目成员走 V58 asset_project_members（owner 不落成员表）。
--   2. narrative_roles JSONB = 项目自定义「叙事角色桶」（受控词汇），默认五桶 [人物,道具,场景,风格,通用]。
--      由 owner/editor 维护，防"标签腐烂"（设计方案 §二/§七 7.2）。
--   3. 继承 BaseEntity（created_by/at + updated_by/at + deleted + version）；deleted 软删（@TableLogic）。
--   4. 删项目 = 级联软删资产/成员/绑定（L4，service 层处理）；物理删除由子表 ON DELETE CASCADE 兜底。
-- 索引（plan 性能清单）：owner_id + updated_at 部分索引支撑「我的项目」列表。
-- ============================================================

CREATE TABLE asset_projects (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by       BIGINT,
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    deleted          INTEGER                     NOT NULL DEFAULT 0,
    version          INTEGER                     NOT NULL DEFAULT 0,
    owner_id         BIGINT                      NOT NULL,                       -- 项目所有者（唯一所有者，与 created_by 解耦便于转让）
    name             VARCHAR(100)                NOT NULL,                       -- 项目名（≤100，安全清单）
    description      TEXT,                                                        -- 项目描述（可选）
    cover_file_id    VARCHAR(64),                                                 -- 封面图（指 stored_files.file_id，可空）
    narrative_roles  JSONB                       NOT NULL DEFAULT '["人物","道具","场景","风格","通用"]'::jsonb  -- 叙事角色受控词汇桶（双轴矩阵 轴B）
);

-- 「我的项目」列表：按 owner 列、最近更新优先；仅未软删
CREATE INDEX idx_asset_project_owner_time ON asset_projects(owner_id, updated_at) WHERE deleted = 0;

COMMENT ON TABLE  asset_projects                       IS '项目资产库·项目（资产的命名空间与授权边界）。双轴矩阵的容器。';
COMMENT ON COLUMN asset_projects.owner_id              IS '项目所有者；权限咽喉点 AssetAclService.loadAccessible 三判之一（owner_id==user→OWNER）';
COMMENT ON COLUMN asset_projects.narrative_roles       IS '叙事角色受控词汇桶 JSON 数组，默认五桶；由 owner/editor 维护（防标签腐烂）';
COMMENT ON COLUMN asset_projects.cover_file_id         IS '封面图 stored_files.file_id（可空，前端展示用）';
COMMENT ON COLUMN asset_projects.deleted               IS '软删标记（@TableLogic）；删项目级联软删资产/成员/绑定（L4）';

-- ============================================================
-- 回滚（rollback）：
-- DROP TABLE IF EXISTS asset_projects;
-- ============================================================
