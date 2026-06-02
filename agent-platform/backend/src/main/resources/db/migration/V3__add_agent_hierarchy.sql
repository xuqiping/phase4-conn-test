-- V3: Agent层级支持 — 添加parent_id实现多层路由
ALTER TABLE agents ADD COLUMN parent_id BIGINT;
ALTER TABLE agents ADD CONSTRAINT fk_agents_parent FOREIGN KEY (parent_id) REFERENCES agents(id);
CREATE INDEX idx_agents_parent_id ON agents(parent_id) WHERE deleted = 0;
