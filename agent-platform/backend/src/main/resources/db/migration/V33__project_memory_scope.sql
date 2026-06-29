-- V33: 项目记忆 scope（速查表09 待增修改 8）。
-- 引入 Project 概念：总记忆(global) + 单独项目记忆，一条记忆可同时属多个 scope。
-- scope = 多对多「可见性」标签（非所有权分区）：
--   user_memories.is_global 标总记忆可见性 + user_memory_projects 关联挂多个 project。
-- 唯一索引 V29 (user_id, memory_key) WHERE conflict_id IS NULL 不动（冲突按 user+key 在可见集内判，与 scope 正交）。
-- 老数据向后兼容：老 user_memories 行 is_global 默认 true（= 今天行为）；老 chat_sessions project_id NULL = global 会话。

-- 1. 项目表（owner = created_by，照 BaseEntity 范式 + agents 表列约定）
CREATE TABLE projects (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    name        VARCHAR(100)                NOT NULL,
    description VARCHAR(500),
    icon        VARCHAR(50),
    sort_order  INT                         NOT NULL DEFAULT 0,
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0
);

COMMENT ON TABLE  projects IS '项目表（记忆 scope 容器，用户私有+可共享）';
COMMENT ON COLUMN projects.created_by IS 'owner（创建者）';

CREATE INDEX idx_projects_owner ON projects(created_by) WHERE deleted = 0;

-- 2. 项目成员表（共享：owner 可邀请他人；role: OWNER/EDITOR/VIEWER）
CREATE TABLE project_members (
    id          BIGINT                      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    project_id  BIGINT                      NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
    user_id     BIGINT                      NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    role        VARCHAR(20)                 NOT NULL DEFAULT 'VIEWER'
                                            CHECK (role IN ('OWNER', 'EDITOR', 'VIEWER')),
    created_by  BIGINT,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    deleted     INT                         NOT NULL DEFAULT 0,
    version     INT                         NOT NULL DEFAULT 0,
    CONSTRAINT uk_project_members_project_user UNIQUE (project_id, user_id)
);

CREATE INDEX idx_project_members_user    ON project_members(user_id)    WHERE deleted = 0;
CREATE INDEX idx_project_members_project ON project_members(project_id) WHERE deleted = 0;

COMMENT ON TABLE project_members IS '项目成员（共享授权）';

-- 3. 记忆↔项目 关联表（多对多可见性，一条记忆可挂多个 project）
CREATE TABLE user_memory_projects (
    memory_id   BIGINT                      NOT NULL REFERENCES user_memories(id) ON DELETE CASCADE,
    project_id  BIGINT                      NOT NULL REFERENCES projects(id)     ON DELETE CASCADE,
    created_at  TIMESTAMP WITH TIME ZONE    NOT NULL DEFAULT NOW(),
    PRIMARY KEY (memory_id, project_id)
);

CREATE INDEX idx_user_memory_projects_project ON user_memory_projects(project_id);
CREATE INDEX idx_user_memory_projects_memory  ON user_memory_projects(memory_id);

COMMENT ON TABLE user_memory_projects IS '记忆-项目可见性关联（多对多 scope 标签）';

-- 4. user_memories 加 is_global（总记忆可见性开关，老行默认 true = 今天行为）
ALTER TABLE user_memories ADD COLUMN is_global BOOLEAN NOT NULL DEFAULT true;
COMMENT ON COLUMN user_memories.is_global IS '是否在总记忆(global) scope 可见，默认 true（老行=今天行为）。项目记忆=false，经 user_memory_projects 挂项目。';

-- 5. chat_sessions 加 project scope 字段
--    project_id         = 写目标（新事实落这，NULL=global 会话）
--    mem_include_global = 读开关：总记忆 on/off（默认 true）
--    mem_read_project_ids = 读开关：开启读取的项目集合（BIGINT[]）
ALTER TABLE chat_sessions ADD COLUMN project_id         BIGINT;
ALTER TABLE chat_sessions ADD COLUMN mem_include_global BOOLEAN NOT NULL DEFAULT true;
ALTER TABLE chat_sessions ADD COLUMN mem_read_project_ids BIGINT[];
COMMENT ON COLUMN chat_sessions.project_id           IS '项目记忆写目标（新事实落这，NULL=总记忆会话）';
COMMENT ON COLUMN chat_sessions.mem_include_global   IS '读开关：是否注入总记忆，默认 true';
COMMENT ON COLUMN chat_sessions.mem_read_project_ids IS '读开关：开启读取的项目 id 集合（扁平对称开关集）';
