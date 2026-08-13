-- RAG 索引快照登记与路由状态。PG 保存控制面真相，服务重启后仍可切换/回滚。
CREATE TABLE IF NOT EXISTS rag_index_snapshots (
    tenant_id BIGINT NOT NULL DEFAULT 1,
    kb_id BIGINT NOT NULL,
    snapshot_id VARCHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'REGISTERED',
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, kb_id, snapshot_id)
);

CREATE TABLE IF NOT EXISTS rag_index_routes (
    tenant_id BIGINT NOT NULL DEFAULT 1,
    kb_id BIGINT NOT NULL,
    active_snapshot_id VARCHAR(64),
    previous_snapshot_id VARCHAR(64),
    config_version BIGINT NOT NULL DEFAULT 0,
    updated_by BIGINT,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (tenant_id, kb_id)
);

CREATE INDEX IF NOT EXISTS idx_rag_index_snapshots_status
    ON rag_index_snapshots(tenant_id, kb_id, status);
