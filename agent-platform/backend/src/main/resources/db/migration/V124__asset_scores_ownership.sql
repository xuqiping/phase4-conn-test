-- ============================================================
-- V124: 2x 第三轮问题修复 C5 —— 共享知识库评分 + 项目开关列 + created_by 存量回填
-- 背景：③ 资产库共享可编辑知识库三问——
--   a) 被授权人（EDITOR）可上传，但 PERSONAL 模式下仅能删改自己上传的内容 → assets.created_by 必须有值；
--      现状坑：该列（V57）自建表起 create/upload/copyCurrent 各路径从不写值（MetaObjectHandler 也不填）→ 存量大量 NULL。
--   b) OWNER 可给所有内容打分（百分制），其他被授权者也可评分；OWNER 可开关成员打分（默认关）。
--   c) 拥有者分与被授权者均分双轨展示。
-- 决策 D1：项目级开关 content_mode=SHARED(默认)/PERSONAL——存量项目 SHARED，行为与升级前完全一致。
-- 决策 D4：成员被移除后历史评分保留并参与均分。
-- ============================================================

-- 1) asset_projects 两列：成员打分开关（默认 FALSE）+ 内容模式（默认 SHARED，受控词汇）
ALTER TABLE asset_projects
    ADD COLUMN member_scoring_enabled BOOLEAN   NOT NULL DEFAULT FALSE,
    ADD COLUMN content_mode           VARCHAR(10) NOT NULL DEFAULT 'SHARED';

ALTER TABLE asset_projects
    ADD CONSTRAINT ck_asset_project_content_mode CHECK (content_mode IN ('SHARED', 'PERSONAL'));

COMMENT ON COLUMN asset_projects.member_scoring_enabled IS '2x第三轮C5：OWNER 是否开放成员打分（默认 FALSE 关；D5 无关，仅 OWNER 可改，requireManage 端点在 C6）';
COMMENT ON COLUMN asset_projects.content_mode           IS '2x第三轮C5：SHARED=成员可删改所有内容（存量行为）；PERSONAL=EDITOR 仅能删改自己上传的内容（按 assets.created_by 判定）';

-- 2) 评分表：每人每资产一票（UNIQUE），百分制 CHECK，is_owner_score 区分拥有者分/被授权者分双轨
--    生活比喻：一块白板（资产）前每人一支笔（每人一票），OWNER 的笔写在专属一栏（is_owner_score），
--    其余人的笔迹算「大众分」栏；改主意 = 擦掉自己那行重写（upsert ON CONFLICT），不是再写一行。
CREATE TABLE asset_scores (
    id               BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by       BIGINT,
    created_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    updated_by       BIGINT,
    updated_at       TIMESTAMPTZ                 NOT NULL DEFAULT NOW(),
    deleted          INTEGER                     NOT NULL DEFAULT 0,             -- 软删（@TableLogic）；被移除成员的评分保留参与均分（D4），不物理删
    version          INTEGER                     NOT NULL DEFAULT 0,             -- MyBatis-Plus 乐观锁行版本
    asset_id         BIGINT                      NOT NULL,                       -- 所属资产（FK assets）
    project_id       BIGINT                      NOT NULL,                       -- 冗余项目 id（按项目批量聚合均分免回表；与 assets.project_id 一致）
    scorer_user_id   BIGINT                      NOT NULL,                       -- 打分人（成员或 OWNER）
    score            SMALLINT                    NOT NULL,                       -- 百分制 0-100
    is_owner_score   BOOLEAN                     NOT NULL DEFAULT FALSE,         -- TRUE=拥有者分（独立展示）；FALSE=被授权者分（进均分）
    CONSTRAINT fk_score_asset   FOREIGN KEY (asset_id)   REFERENCES assets(id)        ON DELETE CASCADE,
    CONSTRAINT fk_score_project FOREIGN KEY (project_id) REFERENCES asset_projects(id) ON DELETE CASCADE,
    CONSTRAINT ck_asset_score_range CHECK (score BETWEEN 0 AND 100)
);

-- 每人每资产一票：普通唯一（非 partial）——ON CONFLICT (asset_id, scorer_user_id) 的仲裁目标；
-- 软删行也占位，重打分走 upsert 复活（deleted 置回 0），避免软删+重插撞唯一键。
CREATE UNIQUE INDEX uk_asset_scores_asset_scorer ON asset_scores(asset_id, scorer_user_id);

-- 均分/计数热路径只扫未删行（软删行稀疏留档）
CREATE INDEX idx_asset_scores_asset ON asset_scores(asset_id) WHERE deleted = 0;
CREATE INDEX idx_asset_scores_project ON asset_scores(project_id) WHERE deleted = 0;

COMMENT ON TABLE  asset_scores                 IS '2x第三轮C5：资产百分制评分（每人每资产一票）。拥有者分与被授权者均分双轨（is_owner_score）';
COMMENT ON COLUMN asset_scores.is_owner_score  IS 'TRUE=拥有者分（单值独立展示）；FALSE=被授权者分（参与均分）';
COMMENT ON COLUMN asset_scores.scorer_user_id  IS '打分人 id；被移除成员的历史评分保留参与均分（决策 D4）';

-- 3) assets.created_by 存量回填（幂等，可重跑——两步都带 WHERE created_by IS NULL）：
--    3a. 文件类：当前版本 → 版本 file_id → stored_files.owner_user_id（真实上传者）
UPDATE assets a
SET    created_by = sf.owner_user_id
FROM   asset_versions v
JOIN   stored_files sf ON sf.file_id = v.file_id
WHERE  a.id = v.asset_id
  AND  v.version = a.current_version
  AND  a.created_by IS NULL;

--    3b. 其余（文本类 / file_id 已清理的孤儿）：归项目 OWNER（近似值，user-ops 已注明）
UPDATE assets a
SET    created_by = p.owner_id
FROM   asset_projects p
WHERE  a.project_id = p.id
  AND  a.created_by IS NULL;

-- ============================================================
-- 回滚（rollback）：
-- DROP TABLE IF EXISTS asset_scores;
-- ALTER TABLE asset_projects DROP CONSTRAINT IF EXISTS ck_asset_project_content_mode;
-- ALTER TABLE asset_projects DROP COLUMN IF EXISTS content_mode;
-- ALTER TABLE asset_projects DROP COLUMN IF EXISTS member_scoring_enabled;
-- （created_by 回填为单向补值，不回滚——回退版本后仅损失新评分数据，不破坏既有行为）
-- ============================================================
