-- ============================================================
-- V57: 项目资产库 · 资产 + 版本 + 角色·资产关联（assets / asset_versions / asset_role_links）
-- 功能：五类资产 CRUD + 双轴矩阵 + 版本快照（plan §S1/S4/S5 / FR-003/004/006，设计方案 §二/§三/§六）
-- 设计要点：
--   1. assets = 双轴矩阵的主记录：轴A=media_type（PROMPT/SCRIPT/IMAGE/VIDEO/AUDIO 固定五类），
--      轴B=通过 asset_role_links 多对多挂叙事角色（一资产可挂多角色，设计方案 §二"多对多挂载非单父"）。
--   2. asset_versions = 版本快照（不可变历史）。引用资产时锁定某版本（asset_id+version+file_id），
--      资产迭代到新版不影响已引用方（设计方案 §六"版本隔离防冲突"）。
--   3. asset_role_links = 资产↔叙事角色 多对多关联（轻量关联表，无 BaseEntity，硬删行）。
--   4. assets 继承 BaseEntity；版本表/关联表为不可变/轻量表，仅 id+审计列。
--   5. 文件实体复用 stored_files（source=SOURCE_ASSET，见 StoredFileEntity）；content JSONB 存非文件类资产正文/分场结构。
-- 索引（plan 性能清单 + 坑点预判）：
--   - (project_id, media_type, updated_at) 部分索引缩圈矩阵筛选；角色过滤走 role_links 关系表（不查 JSONB）。
--   - role_links(asset_id) 支撑 N+1 批查组装；versions(asset_id) 支撑版本时间线。
-- ============================================================

-- 1. 资产主表
CREATE TABLE assets (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by       BIGINT,
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    deleted          INTEGER                     NOT NULL DEFAULT 0,
    version          INTEGER                     NOT NULL DEFAULT 0,            -- MyBatis-Plus 乐观锁行版本（与 current_version 域版本不同）
    project_id       BIGINT                      NOT NULL,                       -- 所属项目（FK asset_projects，授权边界）
    media_type       VARCHAR(16)                 NOT NULL,                       -- 轴A 内容类型：PROMPT/SCRIPT/IMAGE/VIDEO/AUDIO
    name             VARCHAR(100)                NOT NULL,                       -- 资产名（≤100，安全清单）
    description      TEXT,                                                        -- 描述层·用户可编辑
    tags             JSONB                       NOT NULL DEFAULT '[]'::jsonb,    -- 第三自由层·标签数组（临时/探索性标记）
    status           VARCHAR(16)                 NOT NULL DEFAULT 'DRAFT',       -- 生命周期状态机：DRAFT/LOCKED/ARCHIVED（设计方案 §六）
    content          JSONB                       NOT NULL DEFAULT '{}'::jsonb,   -- 非文件类资产正文：提示词正文/剧本分场结构/一致性包字段
    gen_meta         JSONB                       NOT NULL DEFAULT '{}'::jsonb,   -- 生成谱系：prompt/model/seed/参考资产[]/来源画布节点（可复现性关键）
    current_version  INTEGER                     NOT NULL DEFAULT 1,             -- 域版本号（最新版本，乐观锁并发建版用，plan 坑点预判）
    CONSTRAINT fk_asset_project FOREIGN KEY (project_id)
        REFERENCES asset_projects(id) ON DELETE CASCADE,
    CONSTRAINT chk_asset_media CHECK (media_type IN ('PROMPT','SCRIPT','IMAGE','VIDEO','AUDIO')),
    CONSTRAINT chk_asset_status CHECK (status IN ('DRAFT','LOCKED','ARCHIVED'))
);

-- 矩阵筛选主索引：先缩圈到项目+类型，再按更新时间排（角色过滤走 role_links 不查 JSONB）
CREATE INDEX idx_asset_matrix ON assets(project_id, media_type, updated_at) WHERE deleted = 0;
-- 资产名/标签搜索辅助（项目内 q≤50，中文 LIKE，项目内数据量小可接受，plan 坑点预判）
CREATE INDEX idx_asset_project_name ON assets(project_id, name) WHERE deleted = 0;

COMMENT ON TABLE  assets                  IS '项目资产库·资产主表（双轴矩阵记录）。五类资产 × 多叙事角色。';
COMMENT ON COLUMN assets.media_type       IS '轴A 内容类型：PROMPT(提示词)/SCRIPT(剧本)/IMAGE/VIDEO/AUDIO';
COMMENT ON COLUMN assets.tags             IS '标签数组 JSON（自由层，临时/探索性标记，区别于受控的叙事角色）';
COMMENT ON COLUMN assets.status           IS '状态机：DRAFT(草稿)→LOCKED(已定稿)→ARCHIVED(归档)；定稿后被引用=锁版本快照';
COMMENT ON COLUMN assets.content          IS '非文件类资产正文 JSON：提示词正文/剧本分场结构/一致性包字段（主参考图id/标准描述/参数基线）';
COMMENT ON COLUMN assets.gen_meta         IS '生成谱系 JSON：prompt/model/seed/参考资产id[]/来源画布节点（AI 复现性关键，DAM 共识）';
COMMENT ON COLUMN assets.current_version  IS '域版本号（最新版本号，乐观锁并发建版 WHERE current_version=?，区别于乐观锁行版本 version）';

-- 2. 版本快照表（不可变历史，引用即锁版本）
CREATE TABLE asset_versions (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id         BIGINT                      NOT NULL,                       -- 所属资产（FK assets）
    version          INTEGER                     NOT NULL,                       -- 版本号（从 1 递增）
    file_id          VARCHAR(64),                                                 -- 文件类资产指 stored_files.file_id（文本类可空）
    content          JSONB                       NOT NULL DEFAULT '{}'::jsonb,   -- 该版本正文快照（文本类）/一致性包快照
    change_note      VARCHAR(255),                                                -- 改版说明（可选）
    created_by       BIGINT,
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_version_asset FOREIGN KEY (asset_id)
        REFERENCES assets(id) ON DELETE CASCADE,
    CONSTRAINT uk_asset_version UNIQUE (asset_id, version)
);

CREATE INDEX idx_asset_version_asset ON asset_versions(asset_id);

COMMENT ON TABLE  asset_versions        IS '资产版本快照（不可变历史）。引用资产时锁定某版本，资产升级不影响已引用方（版本隔离）。';
COMMENT ON COLUMN asset_versions.file_id IS '该版本文件 stored_files.file_id（文件类资产；文本类可空，正文在 content）';

-- 3. 资产↔叙事角色 多对多关联（轻量表）
CREATE TABLE asset_role_links (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    asset_id         BIGINT                      NOT NULL,                       -- 所属资产（FK assets）
    role_key         VARCHAR(32)                 NOT NULL,                       -- 叙事角色键（取值来自 asset_projects.narrative_roles 受控词汇）
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_rolelink_asset FOREIGN KEY (asset_id)
        REFERENCES assets(id) ON DELETE CASCADE,
    CONSTRAINT uk_asset_role UNIQUE (asset_id, role_key)
);

CREATE INDEX idx_rolelink_asset ON asset_role_links(asset_id);
CREATE INDEX idx_rolelink_role ON asset_role_links(role_key);

COMMENT ON TABLE asset_role_links IS '资产↔叙事角色 多对多关联（双轴矩阵 轴B 挂载）。一资产可挂多角色（多对多非单父）。';

-- ============================================================
-- 回滚（rollback）：
-- DROP TABLE IF EXISTS asset_role_links;
-- DROP TABLE IF EXISTS asset_versions;
-- DROP TABLE IF EXISTS assets;
-- ============================================================
