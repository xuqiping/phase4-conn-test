-- ============================================================
-- V18: 组织/部门模型（RAG DEPARTMENT 授权前置）
-- 计划10 阶段1。授权对象 USER + ROLE + DEPARTMENT，DEPARTMENT 依赖此表。
-- 单租户阶段 tenant_id 默认 1。部门支持自引用树（parent_id）。
-- ============================================================

CREATE TABLE departments (
    id          BIGSERIAL PRIMARY KEY,
    tenant_id   BIGINT      NOT NULL DEFAULT 1,
    name        VARCHAR(200) NOT NULL,
    code        VARCHAR(100),
    parent_id   BIGINT REFERENCES departments(id) ON DELETE SET NULL,
    description TEXT,
    sort_order  INTEGER     NOT NULL DEFAULT 0,
    status      VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted     INTEGER     NOT NULL DEFAULT 0,
    version     INTEGER     NOT NULL DEFAULT 0,
    CONSTRAINT uk_dept_tenant_code UNIQUE (tenant_id, code)
);
CREATE INDEX idx_dept_parent ON departments(parent_id) WHERE deleted = 0;
CREATE INDEX idx_dept_tenant ON departments(tenant_id) WHERE deleted = 0;

CREATE TABLE user_departments (
    id            BIGSERIAL PRIMARY KEY,
    user_id       BIGINT NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    department_id BIGINT NOT NULL REFERENCES departments(id) ON DELETE CASCADE,
    is_primary    BOOLEAN NOT NULL DEFAULT FALSE,
    created_by    BIGINT,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted       INTEGER NOT NULL DEFAULT 0,
    version       INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_user_dept UNIQUE (user_id, department_id)
);
CREATE INDEX idx_user_dept_user ON user_departments(user_id) WHERE deleted = 0;
CREATE INDEX idx_user_dept_dept ON user_departments(department_id) WHERE deleted = 0;
