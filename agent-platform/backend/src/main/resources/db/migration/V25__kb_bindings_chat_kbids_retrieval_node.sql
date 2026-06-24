-- =====================================================================
-- V25：阶段5 — KB 绑定 + chat_sessions.kb_ids + RETRIEVAL 工作流节点类型
-- 依据：计划10 阶段5 / v6 §2.4（检索节点回调）/ §5.1（P4 求交）
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. chat_sessions.kb_ids：CHAT 模式检索 scope（BIGINT[] 原生数组，OLTP 热表免 JSON 解析）
-- ---------------------------------------------------------------------
ALTER TABLE chat_sessions
    ADD COLUMN kb_ids BIGINT[] NOT NULL DEFAULT '{}';

-- ---------------------------------------------------------------------
-- 2. agent_kb_bindings：Agent ↔ KB 检索范围绑定（mirror V16 agent_permissions）
--    Agent.config 不放检索范围（权限敏感，连表更可审计）；P4 = 执行身份权限 ∩ 此绑定
-- ---------------------------------------------------------------------
CREATE TABLE agent_kb_bindings (
    id          BIGSERIAL PRIMARY KEY,
    agent_id    BIGINT NOT NULL REFERENCES agents(id) ON DELETE CASCADE,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id   BIGINT NOT NULL DEFAULT 1,
    granted_by  BIGINT REFERENCES users(id),
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted     INTEGER NOT NULL DEFAULT 0,
    version     INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_agent_kb_bindings_agent_kb UNIQUE (agent_id, kb_id)
);

CREATE INDEX idx_agent_kb_bindings_kb ON agent_kb_bindings(kb_id) WHERE deleted = 0;
CREATE INDEX idx_agent_kb_bindings_agent ON agent_kb_bindings(agent_id) WHERE deleted = 0;

-- ---------------------------------------------------------------------
-- 3. workflow_kb_bindings：Workflow 级 KB 检索范围绑定（与 Agent 对称）
--    注：per-node kbIds 留后续（RETRIEVAL 节点自身 config 携 kbId/kbIds）
-- ---------------------------------------------------------------------
CREATE TABLE workflow_kb_bindings (
    id          BIGSERIAL PRIMARY KEY,
    workflow_id BIGINT NOT NULL REFERENCES workflows(id) ON DELETE CASCADE,
    kb_id       BIGINT NOT NULL REFERENCES knowledge_bases(id) ON DELETE CASCADE,
    tenant_id   BIGINT NOT NULL DEFAULT 1,
    granted_by  BIGINT REFERENCES users(id),
    created_by  BIGINT,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_by  BIGINT,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    deleted     INTEGER NOT NULL DEFAULT 0,
    version     INTEGER NOT NULL DEFAULT 0,
    CONSTRAINT uk_workflow_kb_bindings_workflow_kb UNIQUE (workflow_id, kb_id)
);

CREATE INDEX idx_workflow_kb_bindings_kb ON workflow_kb_bindings(kb_id) WHERE deleted = 0;
CREATE INDEX idx_workflow_kb_bindings_workflow ON workflow_kb_bindings(workflow_id) WHERE deleted = 0;

-- ---------------------------------------------------------------------
-- 4. workflow_nodes 新增 RETRIEVAL 节点类型（v6 §2.4：sidecar 遇该节点回调 Java RagRetrievalService）
-- ---------------------------------------------------------------------
ALTER TABLE workflow_nodes
    DROP CONSTRAINT IF EXISTS workflow_nodes_type_check;

ALTER TABLE workflow_nodes
    ADD CONSTRAINT workflow_nodes_type_check
        CHECK (type IN (
            'START',
            'END',
            'INPUT',
            'SKILL',
            'AGENT',
            'AGENT_REF',
            'WORKFLOW_REF',
            'ROUTER',
            'CONDITION',
            'PARALLEL',
            'JOIN',
            'LOOP',
            'HUMAN_APPROVAL',
            'TOOL_CALL',
            'RETRIEVAL'
        ));
