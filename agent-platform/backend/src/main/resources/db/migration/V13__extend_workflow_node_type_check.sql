-- Allow runtime composition node types saved by the workflow editor.
ALTER TABLE workflow_nodes
    DROP CONSTRAINT IF EXISTS workflow_nodes_type_check;

ALTER TABLE workflow_nodes
    ADD CONSTRAINT workflow_nodes_type_check
        CHECK (type IN (
            'START',
            'END',
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
            'TOOL_CALL'
        ));
