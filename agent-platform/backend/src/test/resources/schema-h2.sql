-- agent-platform/backend/src/test/resources/schema-h2.sql
-- H2兼容模式下的建表脚本（用于测试环境）

CREATE TABLE IF NOT EXISTS users (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    email       VARCHAR(100),
    avatar      VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    last_login_at TIMESTAMP,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS roles (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS permissions (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(100) NOT NULL,
    resource    VARCHAR(50)  NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id)
);

CREATE TABLE IF NOT EXISTS agent_groups (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    icon        VARCHAR(50),
    description VARCHAR(500),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS agents (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    avatar      VARCHAR(500),
    group_id    BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    config      TEXT,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS skills (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    agent_id    BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    type        VARCHAR(30)  NOT NULL DEFAULT 'SEQUENCE',
    config      TEXT,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS skill_steps (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    skill_id    BIGINT       NOT NULL,
    step_order  INT          NOT NULL,
    name        VARCHAR(100) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    config      TEXT,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS workflows (
    id          BIGINT       AUTO_INCREMENT PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    owner_id    BIGINT       NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by  BIGINT,
    updated_at  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted     INT          NOT NULL DEFAULT 0,
    version     INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS workflow_nodes (
    id            BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL,
    node_id       VARCHAR(50)  NOT NULL,
    type          VARCHAR(30)  NOT NULL,
    position_x    DOUBLE       NOT NULL DEFAULT 0,
    position_y    DOUBLE       NOT NULL DEFAULT 0,
    label         VARCHAR(100),
    config        TEXT,
    created_by    BIGINT,
    created_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by    BIGINT,
    updated_at    TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted       INT          NOT NULL DEFAULT 0,
    version       INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS workflow_edges (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workflow_id     BIGINT       NOT NULL,
    source_node_id  VARCHAR(50)  NOT NULL,
    target_node_id  VARCHAR(50)  NOT NULL,
    source_handle   VARCHAR(50),
    target_handle   VARCHAR(50),
    label           VARCHAR(100),
    condition       TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS execution_logs (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    workflow_id     BIGINT       NOT NULL,
    workflow_name   VARCHAR(100),
    triggered_by    BIGINT       NOT NULL,
    status          VARCHAR(20)  NOT NULL DEFAULT 'RUNNING',
    variables       TEXT,
    node_logs       TEXT,
    started_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at    TIMESTAMP,
    duration        BIGINT,
    error_message   TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_by      BIGINT,
    updated_at      TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    deleted         INT          NOT NULL DEFAULT 0,
    version         INT          NOT NULL DEFAULT 0
);

-- 测试初始数据
INSERT INTO roles (id, name, code, description) VALUES (1, '普通用户', 'user', '可以创建和执行工作流');
INSERT INTO roles (id, name, code, description) VALUES (2, 'Agent管理员', 'agent_admin', '可以管理Agent和技能');
INSERT INTO roles (id, name, code, description) VALUES (3, '系统管理员', 'admin', '拥有所有权限');

INSERT INTO permissions (id, name, code, resource, action) VALUES
    (1, '查看Agent', 'agent:read', 'agent', 'read'),
    (2, '创建Agent', 'agent:create', 'agent', 'create'),
    (3, '编辑Agent', 'agent:update', 'agent', 'update'),
    (4, '删除Agent', 'agent:delete', 'agent', 'delete'),
    (5, '发布Agent', 'agent:publish', 'agent', 'publish'),
    (6, '管理技能', 'skill:manage', 'skill', 'manage'),
    (7, '查看工作流', 'workflow:read', 'workflow', 'read'),
    (8, '创建工作流', 'workflow:create', 'workflow', 'create'),
    (9, '编辑工作流', 'workflow:update', 'workflow', 'update'),
    (10, '删除工作流', 'workflow:delete', 'workflow', 'delete'),
    (11, '发布工作流', 'workflow:publish', 'workflow', 'publish'),
    (12, '执行工作流', 'execution:run', 'execution', 'run'),
    (13, '查看执行日志', 'execution:read', 'execution', 'read'),
    (14, '管理用户', 'user:manage', 'user', 'manage'),
    (15, '管理角色', 'role:manage', 'role', 'manage');

-- admin拥有所有权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT 3, id FROM permissions;

-- 测试admin用户（密码: admin123）
INSERT INTO users (id, username, password, email, status)
VALUES (1, 'admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'admin@platform.com', 'ACTIVE');

INSERT INTO user_roles (user_id, role_id) VALUES (1, 3);
