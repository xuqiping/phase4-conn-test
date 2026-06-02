# 数据库 + API First 设计

## 1. 概述

本文档定义多 Agent 智能体平台的数据库表结构和 RESTful API 接口设计。采用 API First 方法，先定义接口契约再实现。

---

## 2. 数据库设计

### 2.1 ER 关系图

```
┌──────────┐     ┌──────────┐     ┌──────────────┐     ┌──────────────────┐
│  users   │     │  roles   │     │ permissions  │     │ role_permissions │
│──────────│     │──────────│     │──────────────│     │──────────────────│
│ id (PK)  │     │ id (PK)  │     │ id (PK)      │     │ role_id (FK,PK)  │
│ username │     │ name     │     │ name         │     │ permission_id    │
│ password │     │ code     │     │ code         │     │      (FK,PK)     │
│ email    │     │ desc     │     │ resource     │     └──────────────────┘
│ status   │     └──────────┘     │ action       │
└──────────┘         │            └──────────────┘
      │              │                   │
      │    ┌──────────────────┐          │
      │    │   user_roles     │──────────┘
      │    │──────────────────│
      └────│ user_id (FK,PK)  │
           │ role_id (FK,PK)  │
           └──────────────────┘

┌──────────────┐     ┌──────────┐     ┌──────────┐     ┌──────────────┐
│ agent_groups │     │  agents  │     │  skills  │     │ skill_steps  │
│──────────────│     │──────────│     │──────────│     │──────────────│
│ id (PK)      │◄────│ id (PK)  │◄────│ id (PK)  │◄────│ id (PK)      │
│ name         │     │ name     │     │ name     │     │ skill_id(FK) │
│ description  │     │ group_id │     │ agent_id │     │ step_order   │
│ sort_order   │     │ status   │     │ type     │     │ name         │
│ created_at   │     │ config   │     │ desc     │     │ action       │
└──────────────┘     │ deleted  │     │ config   │     │ config       │
                     └──────────┘     └──────────┘     └──────────────┘

┌────────────┐     ┌──────────────────┐     ┌──────────────────┐
│ workflows  │     │ workflow_nodes   │     │ workflow_edges   │
│────────────│     │──────────────────│     │──────────────────│
│ id (PK)    │◄────│ id (PK)          │     │ id (PK)          │
│ name       │     │ workflow_id (FK) │     │ workflow_id (FK) │
│ desc       │     │ node_id          │     │ source_node_id   │
│ status     │     │ type             │     │ target_node_id   │
│ owner_id   │     │ position_x       │     │ source_handle    │
│ created_at │     │ position_y       │     │ target_handle    │
│ updated_at │     │ label            │     │ label            │
│ deleted    │     │ config (JSONB)   │     │ condition        │
└────────────┘     └──────────────────┘     └──────────────────┘

┌─────────────────┐
│ execution_logs  │
│─────────────────│
│ id (PK)         │
│ workflow_id (FK)│
│ workflow_name   │
│ triggered_by    │
│ status          │
│ variables       │
│ node_logs(JSONB)│
│ started_at      │
│ completed_at    │
│ duration        │
│ error_message   │
│ created_at      │
└─────────────────┘
```

### 2.2 完整 DDL

```sql
-- ============================================================
-- 多 Agent 智能体平台 数据库初始化脚本
-- 数据库: PostgreSQL 15+
-- 字符集: UTF-8
-- ============================================================

-- ============================================================
-- 1. 认证模块 (auth)
-- ============================================================

-- 1.1 用户表
CREATE TABLE users (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    username    VARCHAR(50)  NOT NULL,
    password    VARCHAR(100) NOT NULL,
    email       VARCHAR(100),
    avatar      VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE'
                              CHECK (status IN ('ACTIVE', 'DISABLED', 'LOCKED')),
    last_login_at TIMESTAMP WITH TIME ZONE,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

COMMENT ON TABLE users IS '用户表';
COMMENT ON COLUMN users.password IS 'BCrypt加密密码';
COMMENT ON COLUMN users.status IS '用户状态: ACTIVE-正常, DISABLED-禁用, LOCKED-锁定';

-- 1.2 角色表
CREATE TABLE roles (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(50)  NOT NULL,
    code        VARCHAR(50)  NOT NULL,
    description VARCHAR(200),
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_roles_code UNIQUE (code)
);

COMMENT ON TABLE roles IS '角色表';

-- 1.3 权限表
CREATE TABLE permissions (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    code        VARCHAR(100) NOT NULL,
    resource    VARCHAR(50)  NOT NULL,
    action      VARCHAR(50)  NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT uk_permissions_code UNIQUE (code)
);

COMMENT ON TABLE permissions IS '权限表';
COMMENT ON COLUMN permissions.resource IS '资源标识: agent, workflow, user, role, execution';
COMMENT ON COLUMN permissions.action IS '操作: create, read, update, delete, publish, execute, manage';

-- 1.4 用户-角色关联表
CREATE TABLE user_roles (
    user_id     BIGINT NOT NULL,
    role_id     BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE
);

COMMENT ON TABLE user_roles IS '用户-角色关联表';

-- 1.5 角色-权限关联表
CREATE TABLE role_permissions (
    role_id       BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles(id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions(id) ON DELETE CASCADE
);

COMMENT ON TABLE role_permissions IS '角色-权限关联表';

-- ============================================================
-- 2. Agent 管理模块 (agent)
-- ============================================================

-- 2.1 Agent 分组表
CREATE TABLE agent_groups (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    icon        VARCHAR(50),
    description VARCHAR(500),
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);

COMMENT ON TABLE agent_groups IS 'Agent分组表';

-- 2.2 Agent 表
CREATE TABLE agents (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    avatar      VARCHAR(500),
    group_id    BIGINT       NOT NULL,
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT', 'PUBLISHED', 'OFFLINE')),
    config      JSONB,
    created_by  BIGINT       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted     INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_agents_group FOREIGN KEY (group_id) REFERENCES agent_groups(id),
    CONSTRAINT fk_agents_creator FOREIGN KEY (created_by) REFERENCES users(id)
);

COMMENT ON TABLE agents IS 'Agent表';
COMMENT ON COLUMN agents.config IS 'Agent配置(JSONB): {model, temperature, maxTokens, systemPrompt}';
COMMENT ON COLUMN agents.deleted IS '逻辑删除: 0-正常, 1-已删除';

CREATE INDEX idx_agents_group_id ON agents(group_id);
CREATE INDEX idx_agents_status ON agents(status) WHERE deleted = 0;
CREATE INDEX idx_agents_created_by ON agents(created_by);

-- 2.3 技能表
CREATE TABLE skills (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    agent_id    BIGINT       NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    type        VARCHAR(30)  NOT NULL DEFAULT 'SEQUENCE'
                              CHECK (type IN ('SEQUENCE', 'CONDITION', 'PARALLEL')),
    config      JSONB,
    sort_order  INT          NOT NULL DEFAULT 0,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted     INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_skills_agent FOREIGN KEY (agent_id) REFERENCES agents(id)
);

COMMENT ON TABLE skills IS '技能表';
COMMENT ON COLUMN skills.type IS '执行类型: SEQUENCE-顺序, CONDITION-条件, PARALLEL-并行';

CREATE INDEX idx_skills_agent_id ON skills(agent_id) WHERE deleted = 0;

-- 2.4 技能步骤表
CREATE TABLE skill_steps (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    skill_id    BIGINT       NOT NULL,
    step_order  INT          NOT NULL,
    name        VARCHAR(100) NOT NULL,
    action      VARCHAR(100) NOT NULL,
    config      JSONB,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_skill_steps_skill FOREIGN KEY (skill_id) REFERENCES skills(id)
);

COMMENT ON TABLE skill_steps IS '技能步骤表';
COMMENT ON COLUMN skill_steps.action IS '步骤动作: LLM_CALL, HTTP_REQUEST, CODE_EXECUTE, CONDITION_CHECK';

CREATE INDEX idx_skill_steps_skill_id ON skill_steps(skill_id);

-- ============================================================
-- 3. 工作流编排模块 (workflow)
-- ============================================================

-- 3.1 工作流表
CREATE TABLE workflows (
    id          BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description VARCHAR(500),
    status      VARCHAR(20)  NOT NULL DEFAULT 'DRAFT'
                              CHECK (status IN ('DRAFT', 'PUBLISHED', 'ARCHIVED')),
    owner_id    BIGINT       NOT NULL,
    created_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    deleted     INT          NOT NULL DEFAULT 0,
    CONSTRAINT fk_workflows_owner FOREIGN KEY (owner_id) REFERENCES users(id)
);

COMMENT ON TABLE workflows IS '工作流表';

CREATE INDEX idx_workflows_owner_id ON workflows(owner_id) WHERE deleted = 0;
CREATE INDEX idx_workflows_status ON workflows(status) WHERE deleted = 0;

-- 3.2 工作流节点表
CREATE TABLE workflow_nodes (
    id            BIGINT       GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id   BIGINT       NOT NULL,
    node_id       VARCHAR(50)  NOT NULL,
    type          VARCHAR(30)  NOT NULL
                                 CHECK (type IN ('START', 'END', 'AGENT', 'CONDITION', 'PARALLEL', 'LOOP')),
    position_x    DOUBLE PRECISION NOT NULL DEFAULT 0,
    position_y    DOUBLE PRECISION NOT NULL DEFAULT 0,
    label         VARCHAR(100),
    config        JSONB,
    created_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at    TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_workflow_nodes_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT uk_workflow_node_id UNIQUE (workflow_id, node_id)
);

COMMENT ON TABLE workflow_nodes IS '工作流节点表';
COMMENT ON COLUMN workflow_nodes.node_id IS '画布节点唯一标识(UUID)';
COMMENT ON COLUMN workflow_nodes.config IS '节点配置(JSONB): AGENT类型含agentId, CONDITION类型含expression等';

CREATE INDEX idx_workflow_nodes_workflow_id ON workflow_nodes(workflow_id);

-- 3.3 工作流边表
CREATE TABLE workflow_edges (
    id              BIGINT      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    workflow_id     BIGINT      NOT NULL,
    source_node_id  VARCHAR(50) NOT NULL,
    target_node_id  VARCHAR(50) NOT NULL,
    source_handle   VARCHAR(50),
    target_handle   VARCHAR(50),
    label           VARCHAR(100),
    condition       TEXT,
    created_at      TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_workflow_edges_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_source FOREIGN KEY (workflow_id, source_node_id)
        REFERENCES workflow_nodes(workflow_id, node_id) ON DELETE CASCADE,
    CONSTRAINT fk_workflow_edges_target FOREIGN KEY (workflow_id, target_node_id)
        REFERENCES workflow_nodes(workflow_id, node_id) ON DELETE CASCADE
);

COMMENT ON TABLE workflow_edges IS '工作流边表';
COMMENT ON COLUMN workflow_edges.condition IS '条件边表达式(JavaScript)';

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
    created_at      TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_execution_logs_workflow FOREIGN KEY (workflow_id) REFERENCES workflows(id),
    CONSTRAINT fk_execution_logs_user FOREIGN KEY (triggered_by) REFERENCES users(id)
);

COMMENT ON TABLE execution_logs IS '执行日志表';
COMMENT ON COLUMN execution_logs.duration IS '执行耗时(毫秒)';
COMMENT ON COLUMN execution_logs.node_logs IS '节点执行日志(JSONB数组): [{nodeId, type, status, input, output, error, startedAt, completedAt}]';

CREATE INDEX idx_execution_logs_workflow_id ON execution_logs(workflow_id);
CREATE INDEX idx_execution_logs_triggered_by ON execution_logs(triggered_by);
CREATE INDEX idx_execution_logs_status ON execution_logs(status);
CREATE INDEX idx_execution_logs_started_at ON execution_logs(started_at DESC);

-- ============================================================
-- 5. 初始数据
-- ============================================================

-- 5.1 初始角色
INSERT INTO roles (name, code, description) VALUES
    ('普通用户', 'user', '可以创建和执行工作流'),
    ('Agent管理员', 'agent_admin', '可以管理Agent和技能'),
    ('系统管理员', 'admin', '拥有所有权限');

-- 5.2 初始权限
INSERT INTO permissions (name, code, resource, action) VALUES
    -- Agent 资源
    ('查看Agent', 'agent:read', 'agent', 'read'),
    ('创建Agent', 'agent:create', 'agent', 'create'),
    ('编辑Agent', 'agent:update', 'agent', 'update'),
    ('删除Agent', 'agent:delete', 'agent', 'delete'),
    ('发布Agent', 'agent:publish', 'agent', 'publish'),
    -- 技能资源
    ('管理技能', 'skill:manage', 'skill', 'manage'),
    -- 工作流资源
    ('查看工作流', 'workflow:read', 'workflow', 'read'),
    ('创建工作流', 'workflow:create', 'workflow', 'create'),
    ('编辑工作流', 'workflow:update', 'workflow', 'update'),
    ('删除工作流', 'workflow:delete', 'workflow', 'delete'),
    ('发布工作流', 'workflow:publish', 'workflow', 'publish'),
    -- 执行资源
    ('执行工作流', 'execution:run', 'execution', 'run'),
    ('查看执行日志', 'execution:read', 'execution', 'read'),
    -- 用户管理
    ('管理用户', 'user:manage', 'user', 'manage'),
    -- 角色管理
    ('管理角色', 'role:manage', 'role', 'manage');

-- 5.3 角色-权限分配
-- 普通用户权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'user' AND p.code IN (
    'agent:read', 'workflow:read', 'workflow:create', 'workflow:update',
    'workflow:delete', 'workflow:publish', 'execution:run', 'execution:read'
);

-- Agent管理员权限（包含普通用户权限）
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'agent_admin' AND p.code IN (
    'agent:read', 'agent:create', 'agent:update', 'agent:delete', 'agent:publish',
    'skill:manage',
    'workflow:read', 'workflow:create', 'workflow:update', 'workflow:delete', 'workflow:publish',
    'execution:run', 'execution:read'
);

-- 系统管理员拥有所有权限
INSERT INTO role_permissions (role_id, permission_id)
SELECT r.id, p.id FROM roles r, permissions p
WHERE r.code = 'admin';

-- 5.4 初始管理员用户 (密码: admin123)
INSERT INTO users (username, password, email, status) VALUES
    ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'admin@platform.com', 'ACTIVE');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'admin';

-- 5.5 初始 Agent 分组
INSERT INTO agent_groups (name, icon, description, sort_order) VALUES
    ('通用助手', '🤖', '通用对话和问答类Agent', 1),
    ('数据分析', '📊', '数据处理和分析类Agent', 2),
    ('内容创作', '✍️', '文案、翻译等创作类Agent', 3),
    ('开发工具', '🛠️', '代码生成和调试类Agent', 4);
```

---

## 3. API 端点列表

### 3.1 API 总览

| 模块 | 前缀 | 端点数 | 说明 |
|------|------|--------|------|
| 认证 | `/api/auth` | 5 | 登录、登出、刷新、注册、用户信息 |
| 用户管理 | `/api/users` | 4 | CRUD（管理员） |
| 角色管理 | `/api/roles` | 4 | CRUD（管理员） |
| 权限管理 | `/api/permissions` | 2 | 列表、按角色查询 |
| Agent 分组 | `/api/agent-groups` | 5 | CRUD + 排序 |
| Agent | `/api/agents` | 6 | CRUD + 发布 + 列表 |
| 技能 | `/api/agents/{id}/skills` | 5 | CRUD + 排序 |
| 技能步骤 | `/api/skills/{id}/steps` | 4 | CRUD + 排序 |
| 工作流 | `/api/workflows` | 5 | CRUD + 发布 |
| 画布 | `/api/workflows/{id}/canvas` | 2 | 保存 + 获取 |
| 执行 | `/api/executions` | 4 | 执行 + 查询 + 取消 + 重试 |

### 3.2 认证模块 API

| 方法 | 端点 | 说明 | 认证 |
|------|------|------|------|
| POST | `/api/auth/login` | 用户登录 | 否 |
| POST | `/api/auth/logout` | 用户登出 | 是 |
| POST | `/api/auth/refresh` | 刷新Token | 是(RefreshToken) |
| POST | `/api/auth/register` | 用户注册 | 否 |
| GET | `/api/auth/me` | 获取当前用户信息 | 是 |

### 3.3 用户管理 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/users` | 用户列表(分页) | 是 | user:manage |
| GET | `/api/users/{id}` | 用户详情 | 是 | user:manage |
| POST | `/api/users` | 创建用户 | 是 | user:manage |
| PUT | `/api/users/{id}` | 更新用户 | 是 | user:manage |

### 3.4 角色管理 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/roles` | 角色列表 | 是 | role:manage |
| GET | `/api/roles/{id}` | 角色详情(含权限) | 是 | role:manage |
| POST | `/api/roles` | 创建角色 | 是 | role:manage |
| PUT | `/api/roles/{id}` | 更新角色权限 | 是 | role:manage |

### 3.5 Agent 分组 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/agent-groups` | 分组列表(含Agent数量) | 否 | - |
| GET | `/api/agent-groups/{id}` | 分组详情 | 否 | - |
| POST | `/api/agent-groups` | 创建分组 | 是 | agent:create |
| PUT | `/api/agent-groups/{id}` | 更新分组 | 是 | agent:update |
| PUT | `/api/agent-groups/sort` | 批量更新排序 | 是 | agent:update |

### 3.6 Agent API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/agents` | Agent列表(分页+筛选) | 否(游客可看已发布) | - |
| GET | `/api/agents/{id}` | Agent详情(含技能) | 否(游客可看已发布) | - |
| POST | `/api/agents` | 创建Agent | 是 | agent:create |
| PUT | `/api/agents/{id}` | 更新Agent | 是 | agent:update |
| DELETE | `/api/agents/{id}` | 删除Agent(软删除) | 是 | agent:delete |
| POST | `/api/agents/{id}/publish` | 发布Agent | 是 | agent:publish |
| POST | `/api/agents/{id}/offline` | 下线Agent | 是 | agent:publish |

### 3.7 技能 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/agents/{agentId}/skills` | 技能列表 | 是 | skill:manage |
| GET | `/api/skills/{id}` | 技能详情(含步骤) | 是 | skill:manage |
| POST | `/api/agents/{agentId}/skills` | 创建技能 | 是 | skill:manage |
| PUT | `/api/skills/{id}` | 更新技能 | 是 | skill:manage |
| DELETE | `/api/skills/{id}` | 删除技能 | 是 | skill:manage |

### 3.8 技能步骤 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/skills/{skillId}/steps` | 步骤列表 | 是 | skill:manage |
| POST | `/api/skills/{skillId}/steps` | 创建步骤 | 是 | skill:manage |
| PUT | `/api/skill-steps/{id}` | 更新步骤 | 是 | skill:manage |
| PUT | `/api/skills/{skillId}/steps/reorder` | 批量更新排序 | 是 | skill:manage |

### 3.9 工作流 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/workflows` | 工作流列表(分页) | 是 | workflow:read |
| GET | `/api/workflows/{id}` | 工作流详情 | 是 | workflow:read |
| POST | `/api/workflows` | 创建工作流 | 是 | workflow:create |
| PUT | `/api/workflows/{id}` | 更新工作流信息 | 是 | workflow:update |
| DELETE | `/api/workflows/{id}` | 删除工作流 | 是 | workflow:delete |
| POST | `/api/workflows/{id}/publish` | 发布工作流 | 是 | workflow:publish |
| GET | `/api/workflows/{id}/validate` | 验证工作流结构 | 是 | workflow:read |

### 3.10 画布 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| GET | `/api/workflows/{id}/canvas` | 获取画布数据(节点+边) | 是 | workflow:read |
| PUT | `/api/workflows/{id}/canvas` | 保存画布数据(批量更新节点和边) | 是 | workflow:update |

### 3.11 执行 API

| 方法 | 端点 | 说明 | 认证 | 权限 |
|------|------|------|------|------|
| POST | `/api/executions` | 执行工作流 | 是 | execution:run |
| GET | `/api/executions` | 执行日志列表(分页) | 是 | execution:read |
| GET | `/api/executions/{id}` | 执行日志详情 | 是 | execution:read |
| POST | `/api/executions/{id}/cancel` | 取消执行 | 是 | execution:run |

---

## 4. 请求/响应示例

### 4.1 用户登录

```json
// POST /api/auth/login
// Request:
{
  "username": "admin",
  "password": "admin123"
}

// Response 200:
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiJ9...",
    "tokenType": "Bearer",
    "expiresIn": 1800,
    "userInfo": {
      "id": 1,
      "username": "admin",
      "email": "admin@platform.com",
      "avatar": null,
      "roles": ["admin"],
      "permissions": ["agent:read", "agent:create", "agent:update", "agent:delete", "agent:publish", "skill:manage", "workflow:read", "workflow:create", "workflow:update", "workflow:delete", "workflow:publish", "execution:run", "execution:read", "user:manage", "role:manage"]
    }
  }
}

// Response 401:
{
  "code": 401,
  "message": "用户名或密码错误",
  "data": null
}
```

### 4.2 刷新 Token

```json
// POST /api/auth/refresh
// Request:
{
  "refreshToken": "eyJhbGciOiJIUzI1NiJ9..."
}

// Response 200:
{
  "code": 200,
  "message": "success",
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiJ9...(新Token)",
    "expiresIn": 1800
  }
}
```

### 4.3 Agent 列表

```json
// GET /api/agents?page=1&size=10&groupId=1&status=PUBLISHED&keyword=助手
// Response 200:
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [
      {
        "id": 1,
        "name": "智能客服助手",
        "description": "基于大模型的智能客服Agent，支持多轮对话",
        "avatar": "/avatars/agent-1.png",
        "groupId": 1,
        "groupName": "通用助手",
        "status": "PUBLISHED",
        "skillCount": 3,
        "createdAt": "2026-05-20T10:30:00Z",
        "updatedAt": "2026-05-22T14:20:00Z"
      }
    ],
    "total": 14,
    "page": 1,
    "size": 10,
    "pages": 2
  }
}
```

### 4.4 Agent 详情

```json
// GET /api/agents/1
// Response 200:
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1,
    "name": "智能客服助手",
    "description": "基于大模型的智能客服Agent，支持多轮对话",
    "avatar": "/avatars/agent-1.png",
    "group": {
      "id": 1,
      "name": "通用助手"
    },
    "status": "PUBLISHED",
    "config": {
      "model": "gpt-4",
      "temperature": 0.7,
      "maxTokens": 4096,
      "systemPrompt": "你是一个专业的客服助手..."
    },
    "skills": [
      {
        "id": 1,
        "name": "意图识别",
        "description": "识别用户意图并路由到对应流程",
        "type": "SEQUENCE",
        "sortOrder": 1,
        "steps": [
          {
            "id": 1,
            "stepOrder": 1,
            "name": "调用LLM分析意图",
            "action": "LLM_CALL",
            "config": {
              "prompt": "分析以下用户输入的意图: {{input}}",
              "model": "gpt-4"
            }
          },
          {
            "id": 2,
            "stepOrder": 2,
            "name": "返回意图结果",
            "action": "CODE_EXECUTE",
            "config": {
              "code": "return { intent: result.intent, confidence: result.confidence }"
            }
          }
        ]
      }
    ],
    "createdBy": {
      "id": 1,
      "username": "admin"
    },
    "createdAt": "2026-05-20T10:30:00Z",
    "updatedAt": "2026-05-22T14:20:00Z"
  }
}
```

### 4.5 保存画布数据

```json
// PUT /api/workflows/1/canvas
// Request:
{
  "nodes": [
    {
      "nodeId": "node-start-001",
      "type": "START",
      "positionX": 250,
      "positionY": 0,
      "label": "开始",
      "config": {}
    },
    {
      "nodeId": "node-agent-001",
      "type": "AGENT",
      "positionX": 250,
      "positionY": 150,
      "label": "智能客服助手",
      "config": {
        "agentId": 1,
        "skillId": 1,
        "inputMapping": { "query": "{{input.text}}" }
      }
    },
    {
      "nodeId": "node-condition-001",
      "type": "CONDITION",
      "positionX": 250,
      "positionY": 300,
      "label": "判断满意度",
      "config": {
        "expression": "result.score >= 0.8"
      }
    },
    {
      "nodeId": "node-end-001",
      "type": "END",
      "positionX": 250,
      "positionY": 500,
      "label": "结束",
      "config": {}
    }
  ],
  "edges": [
    {
      "sourceNodeId": "node-start-001",
      "targetNodeId": "node-agent-001"
    },
    {
      "sourceNodeId": "node-agent-001",
      "targetNodeId": "node-condition-001"
    },
    {
      "sourceNodeId": "node-condition-001",
      "targetNodeId": "node-end-001",
      "sourceHandle": "true",
      "label": "满意"
    },
    {
      "sourceNodeId": "node-condition-001",
      "targetNodeId": "node-agent-001",
      "sourceHandle": "false",
      "label": "不满意(重试)"
    }
  ]
}

// Response 200:
{
  "code": 200,
  "message": "success",
  "data": {
    "nodeCount": 4,
    "edgeCount": 4,
    "updatedAt": "2026-05-25T08:30:00Z"
  }
}
```

### 4.6 执行工作流

```json
// POST /api/executions
// Request:
{
  "workflowId": 1,
  "variables": {
    "input": { "text": "你好，我想咨询订单状态" }
  }
}

// Response 202:
{
  "code": 202,
  "message": "执行已提交",
  "data": {
    "executionId": 1001,
    "workflowId": 1,
    "workflowName": "客服流程",
    "status": "RUNNING",
    "startedAt": "2026-05-25T08:35:00Z"
  }
}
```

### 4.7 执行日志详情

```json
// GET /api/executions/1001
// Response 200:
{
  "code": 200,
  "message": "success",
  "data": {
    "id": 1001,
    "workflowId": 1,
    "workflowName": "客服流程",
    "triggeredBy": {
      "id": 1,
      "username": "admin"
    },
    "status": "SUCCESS",
    "variables": {
      "input": { "text": "你好，我想咨询订单状态" }
    },
    "nodeLogs": [
      {
        "nodeId": "node-start-001",
        "nodeType": "START",
        "nodeLabel": "开始",
        "status": "SUCCESS",
        "input": null,
        "output": { "text": "你好，我想咨询订单状态" },
        "startedAt": "2026-05-25T08:35:00.000Z",
        "completedAt": "2026-05-25T08:35:00.010Z"
      },
      {
        "nodeId": "node-agent-001",
        "nodeType": "AGENT",
        "nodeLabel": "智能客服助手",
        "status": "SUCCESS",
        "input": { "query": "你好，我想咨询订单状态" },
        "output": {
          "response": "您好！请提供您的订单编号，我来帮您查询。",
          "score": 0.92
        },
        "startedAt": "2026-05-25T08:35:00.020Z",
        "completedAt": "2026-05-25T08:35:03.450Z"
      },
      {
        "nodeId": "node-condition-001",
        "nodeType": "CONDITION",
        "nodeLabel": "判断满意度",
        "status": "SUCCESS",
        "input": { "score": 0.92 },
        "output": { "result": true },
        "startedAt": "2026-05-25T08:35:03.460Z",
        "completedAt": "2026-05-25T08:35:03.470Z"
      },
      {
        "nodeId": "node-end-001",
        "nodeType": "END",
        "nodeLabel": "结束",
        "status": "SUCCESS",
        "input": null,
        "output": { "result": "流程完成" },
        "startedAt": "2026-05-25T08:35:03.480Z",
        "completedAt": "2026-05-25T08:35:03.490Z"
      }
    ],
    "startedAt": "2026-05-25T08:35:00.000Z",
    "completedAt": "2026-05-25T08:35:03.490Z",
    "duration": 3490,
    "errorMessage": null,
    "createdAt": "2026-05-25T08:35:00.000Z"
  }
}
```

---

## 5. 状态码定义

### 5.1 HTTP 状态码使用规范

| HTTP状态码 | 含义 | 使用场景 |
|-----------|------|---------|
| 200 | OK | GET/PUT/DELETE 成功 |
| 201 | Created | POST 创建资源成功 |
| 202 | Accepted | 异步任务已接受（工作流执行） |
| 204 | No Content | DELETE 成功无返回体 |
| 400 | Bad Request | 请求参数校验失败 |
| 401 | Unauthorized | 未认证（Token缺失或无效） |
| 403 | Forbidden | 无权限访问该资源 |
| 404 | Not Found | 资源不存在 |
| 409 | Conflict | 资源冲突（如用户名已存在） |
| 422 | Unprocessable Entity | 业务规则校验失败 |
| 429 | Too Many Requests | 请求频率超限 |
| 500 | Internal Server Error | 服务端内部错误 |

### 5.2 业务状态码定义

所有 API 响应使用统一包装格式：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

| 业务码 | 含义 | 说明 |
|--------|------|------|
| 200 | 成功 | 请求处理成功 |
| 400 | 参数错误 | 请求参数校验失败 |
| 401 | 未认证 | Token无效或已过期 |
| 40101 | Token已过期 | Access Token过期，需要刷新 |
| 40102 | Token已失效 | Token在黑名单中（已登出） |
| 403 | 无权限 | 缺少必要的权限 |
| 40301 | 角色权限不足 | 当前角色无此操作权限 |
| 404 | 资源不存在 | 请求的资源未找到 |
| 409 | 资源冲突 | 数据冲突（唯一约束违反） |
| 422 | 业务规则违反 | 业务逻辑校验失败 |
| 42201 | Agent未发布 | 执行未发布的Agent |
| 42202 | 工作流结构无效 | 工作流验证不通过 |
| 42203 | Agent无技能 | Agent没有配置技能 |
| 429 | 请求频率超限 | 触发限流 |
| 500 | 服务器错误 | 未预期的服务端错误 |

### 5.3 统一响应封装类

```java
@Data
@AllArgsConstructor
@NoArgsConstructor
public class ApiResponse<T> {
    private int code;
    private String message;
    private T data;

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(200, "success", data);
    }

    public static <T> ApiResponse<T> success(String message, T data) {
        return new ApiResponse<>(200, message, data);
    }

    public static <T> ApiResponse<T> error(int code, String message) {
        return new ApiResponse<>(code, message, null);
    }
}
```

### 5.4 分页响应格式

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "records": [],
    "total": 100,
    "page": 1,
    "size": 10,
    "pages": 10
  }
}
```

### 5.5 错误响应示例

```json
// 参数校验错误 (400)
{
  "code": 400,
  "message": "参数校验失败",
  "data": {
    "errors": [
      { "field": "name", "message": "名称不能为空" },
      { "field": "email", "message": "邮箱格式不正确" }
    ]
  }
}

// Token过期 (40101)
{
  "code": 40101,
  "message": "Access Token已过期，请使用Refresh Token刷新",
  "data": null
}

// 权限不足 (40301)
{
  "code": 40301,
  "message": "当前角色缺少权限: agent:publish",
  "data": null
}

// 工作流结构无效 (42202)
{
  "code": 42202,
  "message": "工作流结构验证失败",
  "data": {
    "errors": [
      { "type": "ISOLATED_NODE", "message": "节点 'node-agent-002' 未连接到任何边" },
      { "type": "MISSING_END_NODE", "message": "工作流缺少结束节点" }
    ]
  }
}
```

---

## 6. API 通用规则

### 6.1 请求头

| Header | 值 | 说明 |
|--------|-----|------|
| `Authorization` | `Bearer {accessToken}` | JWT认证Token（登录接口除外） |
| `Content-Type` | `application/json` | 请求体格式 |
| `Accept` | `application/json` | 期望的响应格式 |
| `X-Request-Id` | UUID | 请求追踪ID（可选，服务端自动生成） |

### 6.2 分页参数

| 参数 | 默认值 | 说明 |
|------|--------|------|
| `page` | 1 | 页码（从1开始） |
| `size` | 10 | 每页数量（最大100） |
| `sort` | `created_at` | 排序字段 |
| `order` | `desc` | 排序方向 (asc/desc) |

### 6.3 筛选参数

| 资源 | 筛选参数 | 说明 |
|------|---------|------|
| Agent | `keyword`, `groupId`, `status` | 关键词、分组、状态 |
| 工作流 | `keyword`, `status` | 关键词、状态 |
| 执行日志 | `workflowId`, `status`, `startDate`, `endDate` | 工作流ID、状态、时间范围 |
