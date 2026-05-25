-- ============================================================
-- 多Agent智能体平台 初始数据
-- Flyway迁移: V2
-- ============================================================

-- 2.1 初始角色
INSERT INTO roles (name, code, description) VALUES
    ('普通用户', 'user', '可以创建和执行工作流'),
    ('Agent管理员', 'agent_admin', '可以管理Agent和技能'),
    ('系统管理员', 'admin', '拥有所有权限');

-- 2.2 初始权限
INSERT INTO permissions (name, code, resource, action) VALUES
    ('查看Agent', 'agent:read', 'agent', 'read'),
    ('创建Agent', 'agent:create', 'agent', 'create'),
    ('编辑Agent', 'agent:update', 'agent', 'update'),
    ('删除Agent', 'agent:delete', 'agent', 'delete'),
    ('发布Agent', 'agent:publish', 'agent', 'publish'),
    ('管理技能', 'skill:manage', 'skill', 'manage'),
    ('查看工作流', 'workflow:read', 'workflow', 'read'),
    ('创建工作流', 'workflow:create', 'workflow', 'create'),
    ('编辑工作流', 'workflow:update', 'workflow', 'update'),
    ('删除工作流', 'workflow:delete', 'workflow', 'delete'),
    ('发布工作流', 'workflow:publish', 'workflow', 'publish'),
    ('执行工作流', 'execution:run', 'execution', 'run'),
    ('查看执行日志', 'execution:read', 'execution', 'read'),
    ('管理用户', 'user:manage', 'user', 'manage'),
    ('管理角色', 'role:manage', 'role', 'manage');

-- 2.3 角色-权限分配
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

-- 2.4 初始管理员用户（密码: admin123）
INSERT INTO users (username, password, email, status) VALUES
    ('admin', '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIUi', 'admin@platform.com', 'ACTIVE');

INSERT INTO user_roles (user_id, role_id)
SELECT u.id, r.id FROM users u, roles r
WHERE u.username = 'admin' AND r.code = 'admin';

-- 2.5 初始Agent分组
INSERT INTO agent_groups (name, icon, description, sort_order) VALUES
    ('通用助手', 'robot', '通用对话和问答类Agent', 1),
    ('数据分析', 'chart', '数据处理和分析类Agent', 2),
    ('内容创作', 'edit', '文案、翻译等创作类Agent', 3),
    ('开发工具', 'code', '代码生成和调试类Agent', 4);
