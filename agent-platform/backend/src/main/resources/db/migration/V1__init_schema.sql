-- ============================================================
-- 多Agent智能体平台 数据库初始化脚本
-- 数据库: PostgreSQL 15+
-- Flyway迁移: V1
-- ============================================================

-- ============================================================
-- 1. 认证模块 (auth)
-- ============================================================

-- 1.1 用户表
CREATE TABLE users (
    id            BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username      VARCHAR(50)                 NOT NULL,
    password      VARCHAR(100)                NOT NULL,
    email         VARCHAR(100),
    avatar        VARCHAR(500),
    status        VARCHAR(20)                 NOT NULL DEFAULT 'ACTIVE'
                                               CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted       INT                         NOT NULL DEFAULT 0,
    version       INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email    UNIQUE (email)
);

COMMENT ON TABLE  users              IS '用户表';
COMMENT ON COLUMN users.password     IS 'BCrypt加密密码';
COMMENT ON COLUMN users.status       IS '用户状态: ACTIVE-正常, DISABLED-禁用, LOCKED-锁定';
COMMENT ON COLUMN users.deleted      IS '逻辑删除: 0-正常, 1-已删除';
COMMENT ON COLUMN users.version      IS '乐观锁版本号';

-- 1.2 角色表
CREATE TABLE roles (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50)                 NOT NULL,
    code        VARCHAR(50)                 NOT NULL,
    description VARCHAR(200),
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_roles_code UNIQUE (code)
);

COMMENT ON TABLE roles IS '角色表';

-- 1.3 权限表
CREATE TABLE permissions (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    code        VARCHAR(100)                NOT NULL,
    resource    VARCHAR(50)                 NOT NULL,
    action      VARCHAR(50)                 NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

COMMENT ON TABLE  permissions              IS '权限表';
COMMENT ON COLUMN permissions.resource     IS '资源标识: agent, workflow, user, role, execution';
COMMENT ON COLUMN permissions.action       IS '操作: create, read, update, delete, publish, execute, manage';

-- 1.4 用户-角色关联表
CREATE TABLE user_roles (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id)     REFERENCES users(id)       ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id)     REFERENCES roles(id)       ON DELETE CASCADE
);

COMMENT ON TABLE user_roles IS '用户-角色关联表';

-- 1.5 角色-权限关联表
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role       FOREIGN KEY (role_id)       REFERENCES roles(id)       ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

COMMENT ON TABLE role_permissions IS '角色-权限关联表';

-- ============================================================
-- 2. Agent管理模块 (agent)
-- ============================================================

-- 2.1 Agent分组表
CREATE TABLE agent_groups (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    icon        VARCHAR(50),
    description VARCHAR(500),
    sort_order  INT                         NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0
);

COMMENT ON TABLE agent_groups IS 'Agent分组表';

-- 2.2 Agent表
CREATE TABLE agents (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    avatar      VARCHAR(500),
    group_id    BIGINT                      NOT NULL,
    status      VARCHAR(20)                 NOT NULL DEFAULT 'DRAFT'
                                               CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    config      JSONB,
    created_by  BIGINT                      NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_agents_group   FOREIGN KEY (group_id)   REFERENCES agent_groups(id),
    CONSTRAINT fk_agents_creator FOREIGN KEY (created_by)  REFERENCES users(id)
);

COMMENT ON TABLE  agents           IS 'Agent表';
COMMENT ON COLUMN agents.config    IS 'Agent配置(JSONB): {model, temperature, maxTokens, systemPrompt}';
COMMENT ON COLUMN agents.deleted   IS '逻辑删除: 0-正常, 1-已删除';

CREATE INDEX idx_agents_group_id    ON agents(group_id);
CREATE INDEX idx_agents_status      ON agents(status)      WHERE deleted = 0;
CREATE INDEX idx_agents_created_by  ON agents(created_by);

-- 2.3 技能表
CREATE TABLE skills (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT                      NOT NULL,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    type        VARCHAR(30)                 NOT NULL DEFAULT 'SEQUENCE'
                                               CHECK (type IN ('SEQUENCE', 'CONDITION', 'PARALLEL')),
    config      JSONB,
    sort_order  INT                         NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_skills_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
);

COMMENT ON TABLE  skills        IS '技能表';
COMMENT ON COLUMN skills.type   IS '执行类型: SEQUENCE-顺序, CONDITION-条件, PARALLEL-并行';

CREATE INDEX idx_skills_agent_id ON skills(agent_id) WHERE deleted = 0;

-- 2.4 技能步骤表
CREATE TABLE skill_steps (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skill_id    BIGINT                      NOT NULL,
    step_order  INT                         NOT NULL,
    name        VARCHAR(100)                NOT NULL,
    action      VARCHAR(100)                NOT NULL,
    config      JSONB,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_skill_steps_skill FOREIGN KEY (skill_id) REFERENCES skills(id)
);

COMMENT ON TABLE  skill_steps          IS '技能步骤表';
COMMENT ON COLUMN skill_steps.action   IS '步骤动作: LLM_CALL, HTTP_REQUEST, CODE_EXECUTE, CONDITION_CHECK';

CREATE INDEX idx_skill_steps_skill_id ON skill_steps(skill_id);

-- ============================================================
-- 3. 工作流编排模块 (workflow)
-- ============================================================

-- 3.1 工作流表
CREATE TABLE workflows (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)                 NOT NULL DEFAULT 'DRAFT'
                                               CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    owner_id    BIGINT                      NOT NULL,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflows_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);

COMMENT ON TABLE workflows IS '工作流表';

CREATE INDEX idx_workflows_owner_id ON workflows(owner_id) WHERE deleted = 0;
CREATE INDEX idx_workflows_status   ON workflows(status)   WHERE deleted = 0;

-- 3.2 工作流节点表
CREATE TABLE workflow_nodes (
    id            BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   BIGINT                      NOT NULL,
    node_id       VARCHAR(50)                 NOT NULL,
    type          VARCHAR(30)                 NOT NULL
                                                 CHECK (type IN ('START', 'END', 'AGENT', 'CONDITION', 'PARALLEL', 'LOOP')),
    position_x    DOUBLE PRECISION            NOT NULL DEFAULT 0,
    position_y    DOUBLE PRECISION            NOT NULL DEFAULT 0,
    label         VARCHAR(100),
    config        JSONB,
    created_by    BIGINT,
    created_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by    BIGINT,
    updated_at    TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted       INT                         NOT NULL DEFAULT 0,
    version       INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_nodes_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_node_id         UNIQUE (workflow_id, node_id)
);

COMMENT ON TABLE  workflow_nodes              IS '工作流节点表';
COMMENT ON COLUMN workflow_nodes.node_id      IS '画布节点唯一标识(UUID)';
COMMENT ON COLUMN workflow_nodes.config       IS '节点配置(JSONB): AGENT类型含agentId, CONDITION类型含expression等';

CREATE INDEX idx_workflow_nodes_workflow_id ON workflow_nodes(workflow_id);

-- 3.3 工作流边表
CREATE TABLE workflow_edges (
    id              BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id     BIGINT                      NOT NULL,
    source_node_id  VARCHAR(50)                 NOT NULL,
    target_node_id  VARCHAR(50)                 NOT NULL,
    source_handle   VARCHAR(50),
    target_handle   VARCHAR(50),
    label           VARCHAR(100),
    condition       TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted         INT                         NOT NULL DEFAULT 0,
    version         INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflow_edges_workflow FOREIGN KEY (workflow_id)                      REFERENCES workflows(id)                              ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_source   FOREIGN KEY (workflow_id, source_node_id)     REFERENCES workflow_nodes(workflow_id, node_id)       ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_target   FOREIGN KEY (workflow_id, target_node_id)     REFERENCES workflow_nodes(workflow_id, node_id)       ON DELETE CASCADE
);

COMMENT ON TABLE  workflow_edges                IS '工作流边表';
COMMENT ON COLUMN workflow_edges.condition      IS '条件边表达式(JavaScript)';

CREATE INDEX idx_workflow_edges_workflow_id ON workflow_edges(workflow_id);

-- ============================================================
-- 4. 执行模块 (execution)
-- ============================================================

-- 4.1 执行日志表
CREATE TABLE execution_logs (
    id              BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id     BIGINT                      NOT NULL,
    workflow_name   VARCHAR(100),
    triggered_by    BIGINT                      NOT NULL,
    status          VARCHAR(20)                 NOT NULL DEFAULT 'RUNNING'
                                                 CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED')),
    variables       JSONB,
    node_logs       JSONB,
    started_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    completed_at    TIMESTAMP WITH TIME ZONE,
    duration        BIGINT,
    error_message   TEXT,
    created_by      BIGINT,
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by      BIGINT,
    updated_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted         INT                         NOT NULL DEFAULT 0,
    version         INT                         NOT NULL DEFAULT 0,
    CONSTRAINT fk_execution_logs_workflow FOREIGN KEY (workflow_id)  REFERENCES workflows(id),
    CONSTRAINT fk_execution_logs_user     FOREIGN KEY (triggered_by) REFERENCES users(id)
);

COMMENT ON TABLE  execution_logs                IS '执行日志表';
COMMENT ON COLUMN execution_logs.duration       IS '执行耗时(毫秒)';
COMMENT ON COLUMN execution_logs.node_logs      IS '节点执行日志(JSONB数组): [{nodeId, type, status, input, output, error, startedAt, completedAt}]';

CREATE INDEX idx_execution_logs_workflow_id  ON execution_logs(workflow_id);
CREATE INDEX idx_execution_logs_triggered_by ON execution_logs(triggered_by);
CREATE INDEX idx_execution_logs_status       ON execution_logs(status);
CREATE INDEX idx_execution_logs_started_at   ON execution_logs(started_at DESC);
