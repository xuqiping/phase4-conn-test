-- Workflow node config is handled as a JSON string by Java DTOs/entities.
ALTER TABLE workflow_nodes
    ALTER COLUMN config TYPE TEXT
    USING config::text;
