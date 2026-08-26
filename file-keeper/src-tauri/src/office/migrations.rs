use rusqlite::{Connection, Result};

const MIGRATION_V1: &str = r#"
CREATE TABLE IF NOT EXISTS office_schema_migrations (
    version INTEGER PRIMARY KEY,
    applied_at INTEGER NOT NULL
);

CREATE TABLE IF NOT EXISTS office_tasks (
    id TEXT PRIMARY KEY,
    request_id TEXT,
    task_type TEXT NOT NULL,
    status TEXT NOT NULL,
    engine TEXT,
    output_policy TEXT NOT NULL,
    rule_schema_version INTEGER NOT NULL CHECK (rule_schema_version > 0),
    rule_json TEXT NOT NULL,
    input_count INTEGER NOT NULL CHECK (input_count >= 0),
    total_bytes INTEGER NOT NULL CHECK (total_bytes >= 0),
    output_dir TEXT,
    created_at INTEGER NOT NULL,
    started_at INTEGER,
    finished_at INTEGER
);

CREATE TABLE IF NOT EXISTS office_task_inputs (
    task_id TEXT NOT NULL REFERENCES office_tasks(id) ON DELETE CASCADE,
    input_id TEXT NOT NULL,
    path TEXT NOT NULL,
    fingerprint TEXT NOT NULL,
    format TEXT NOT NULL,
    size_bytes INTEGER NOT NULL CHECK (size_bytes >= 0),
    risk_flags_json TEXT NOT NULL,
    status TEXT NOT NULL,
    error_code TEXT,
    PRIMARY KEY (task_id, input_id)
);

CREATE TABLE IF NOT EXISTS office_task_outputs (
    task_id TEXT NOT NULL REFERENCES office_tasks(id) ON DELETE CASCADE,
    output_id TEXT NOT NULL,
    input_id TEXT,
    temp_path TEXT,
    published_path TEXT,
    checksum TEXT,
    status TEXT NOT NULL,
    PRIMARY KEY (task_id, output_id)
);

CREATE TABLE IF NOT EXISTS office_task_issues (
    task_id TEXT NOT NULL REFERENCES office_tasks(id) ON DELETE CASCADE,
    issue_id TEXT NOT NULL,
    scope TEXT NOT NULL,
    severity TEXT NOT NULL,
    code TEXT NOT NULL,
    message_key TEXT NOT NULL,
    details_json TEXT NOT NULL,
    resolved INTEGER NOT NULL CHECK (resolved IN (0, 1)),
    PRIMARY KEY (task_id, issue_id)
);

CREATE TABLE IF NOT EXISTS office_task_events (
    task_id TEXT NOT NULL REFERENCES office_tasks(id) ON DELETE CASCADE,
    sequence INTEGER NOT NULL,
    stage TEXT NOT NULL,
    progress INTEGER NOT NULL CHECK (progress BETWEEN 0 AND 100),
    event_code TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    PRIMARY KEY (task_id, sequence)
);

CREATE INDEX IF NOT EXISTS idx_office_tasks_created_at
    ON office_tasks(created_at DESC, id);
CREATE INDEX IF NOT EXISTS idx_office_tasks_status_created_at
    ON office_tasks(status, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_office_inputs_task_status
    ON office_task_inputs(task_id, status);
CREATE INDEX IF NOT EXISTS idx_office_issues_task_resolved
    ON office_task_issues(task_id, resolved, severity);
CREATE INDEX IF NOT EXISTS idx_office_events_task_sequence
    ON office_task_events(task_id, sequence);
"#;

pub fn migrate(connection: &mut Connection, applied_at: i64) -> Result<()> {
    connection.execute_batch("PRAGMA foreign_keys = ON;")?;
    let transaction = connection.transaction()?;
    transaction.execute_batch(MIGRATION_V1)?;
    transaction.execute(
        "INSERT OR IGNORE INTO office_schema_migrations(version, applied_at) VALUES (1, ?1)",
        [applied_at],
    )?;
    transaction.commit()
}
