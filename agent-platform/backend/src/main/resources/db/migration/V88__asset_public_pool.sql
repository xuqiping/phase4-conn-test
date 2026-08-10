-- ============================================================
-- V88: 资产公众池发布快照 + 独立访问申请
-- 一行 asset_projects 仍是唯一原项目；公开后不复制项目、资产、版本或文件。
-- asset_public_access_requests 只表达公共只读访问，不进入项目成员表。
-- ============================================================

ALTER TABLE asset_projects
    ADD COLUMN public_pool BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN public_access_mode VARCHAR(24),
    ADD COLUMN published_by BIGINT,
    ADD COLUMN published_at TIMESTAMPTZ,
    ADD COLUMN published_by_admin BOOLEAN NOT NULL DEFAULT FALSE,
    ADD CONSTRAINT chk_asset_project_public_snapshot CHECK (
        (public_pool = FALSE
            AND public_access_mode IS NULL
            AND published_by IS NULL
            AND published_at IS NULL
            AND published_by_admin = FALSE)
        OR
        (public_pool = TRUE
            AND public_access_mode IN ('OPEN', 'APPROVAL_REQUIRED')
            AND published_by IS NOT NULL
            AND published_at IS NOT NULL)
    );

CREATE INDEX idx_asset_project_public_time
    ON asset_projects(published_at DESC)
    WHERE deleted = 0 AND public_pool = TRUE;

CREATE TABLE asset_public_access_requests (
    id           BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    created_by   BIGINT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by   BIGINT,
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted      INTEGER NOT NULL DEFAULT 0,
    version      INTEGER NOT NULL DEFAULT 0,
    project_id   BIGINT NOT NULL,              -- 被申请的公众池项目
    applicant_id BIGINT NOT NULL,              -- 申请使用的用户
    status       VARCHAR(16) NOT NULL,          -- PENDING/APPROVED/REJECTED/REVOKED
    decided_by   BIGINT,                        -- 最近一次决定人；PENDING 时为空
    decided_at   TIMESTAMPTZ,                   -- 最近一次决定时间；PENDING 时为空
    CONSTRAINT fk_asset_public_request_project FOREIGN KEY (project_id)
        REFERENCES asset_projects(id) ON DELETE CASCADE,
    CONSTRAINT chk_asset_public_request_status CHECK (
        status IN ('PENDING', 'APPROVED', 'REJECTED', 'REVOKED')
    ),
    CONSTRAINT chk_asset_public_request_decision CHECK (
        (status = 'PENDING' AND decided_by IS NULL AND decided_at IS NULL)
        OR
        (status IN ('APPROVED', 'REJECTED', 'REVOKED')
            AND decided_by IS NOT NULL AND decided_at IS NOT NULL)
    )
);

-- 同一项目与申请人始终复用一条当前记录；被拒绝/撤销后原行重置为 PENDING。
CREATE UNIQUE INDEX uk_asset_public_request_project_applicant
    ON asset_public_access_requests(project_id, applicant_id)
    WHERE deleted = 0;
CREATE INDEX idx_asset_public_request_project_status
    ON asset_public_access_requests(project_id, status)
    WHERE deleted = 0;
CREATE INDEX idx_asset_public_request_applicant_status
    ON asset_public_access_requests(applicant_id, status)
    WHERE deleted = 0;

COMMENT ON COLUMN asset_projects.public_pool IS '是否发布到公众池；公开读取仍使用原项目与原资产数据';
COMMENT ON COLUMN asset_projects.public_access_mode IS '公开访问模式：OPEN 或 APPROVAL_REQUIRED；未发布为空';
COMMENT ON COLUMN asset_projects.published_by_admin IS '发布当时是否为管理员；作为不可随转让变化的官方标记快照';
COMMENT ON TABLE asset_public_access_requests IS '公众池审批访问申请；与 asset_project_members 完全独立';

-- 回滚说明：上线产生发布/申请数据后不可直接 DROP；应另写迁移先归档数据再清结构。
