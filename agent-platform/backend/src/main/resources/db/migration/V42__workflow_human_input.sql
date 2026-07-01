-- 工作流人机交互节点（HUMAN_INPUT）：支持中途暂停向用户提问、收集答案后续跑。
-- 1) workflow_nodes 放开 HUMAN_INPUT 节点类型
ALTER TABLE workflow_nodes
    DROP CONSTRAINT IF EXISTS workflow_nodes_type_check;

ALTER TABLE workflow_nodes
    ADD CONSTRAINT workflow_nodes_type_check
        CHECK (type IN (
            'START', 'END', 'INPUT', 'SKILL', 'AGENT', 'AGENT_REF', 'WORKFLOW_REF',
            'ROUTER', 'CONDITION', 'PARALLEL', 'JOIN', 'LOOP',
            'HUMAN_APPROVAL', 'HUMAN_INPUT', 'TOOL_CALL', 'RETRIEVAL'
        ));

-- 2) execution_logs 状态放开 WAITING_INPUT / WAITING_APPROVAL（补 V12 漏） / RESUMED
ALTER TABLE execution_logs
    DROP CONSTRAINT IF EXISTS execution_logs_status_check;

ALTER TABLE execution_logs
    ADD CONSTRAINT execution_logs_status_check
        CHECK (status IN ('RUNNING', 'SUCCESS', 'FAILED', 'CANCELLED',
                          'WAITING_APPROVAL', 'WAITING_INPUT', 'RESUMED'));

-- 3) execution_logs 增列：session_id（对话流触发时填充，用于人机输入拦截定位）
ALTER TABLE execution_logs
    ADD COLUMN IF NOT EXISTS session_id BIGINT;

-- 4) execution_logs 增列：pending_input（WAITING_INPUT 期间缓存的待答问题规格 JSONB）
ALTER TABLE execution_logs
    ADD COLUMN IF NOT EXISTS pending_input JSONB;

COMMENT ON COLUMN execution_logs.session_id    IS '触发该执行的聊天会话ID（从对话流触发工作流时填充）';
COMMENT ON COLUMN execution_logs.pending_input IS 'WAITING_INPUT期间待答问题规格(JSONB): {nodeId,inputKey,question,inputType,options,required,checkpointRef}';

-- 拦截查询：按会话定位最近一条 WAITING_INPUT 执行
CREATE INDEX IF NOT EXISTS idx_execution_logs_session_status
    ON execution_logs (session_id, status);
