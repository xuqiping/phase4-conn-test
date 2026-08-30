-- P06 S7：tasks.source 增加 'fix'（验收圈选修复任务，FR-033/AC-037）。
-- SQLite 无法 ALTER CHECK，需重建表。子表（task_events/checkpoints/acceptance_items）
-- 以名字引用 tasks，重建后 id 不变，用 defer_foreign_keys 在事务内安全完成。
PRAGMA defer_foreign_keys = ON;

CREATE TABLE tasks_new (
  id            INTEGER PRIMARY KEY AUTOINCREMENT,
  round_id      INTEGER NOT NULL REFERENCES rounds(id),
  chunk_no      INTEGER NOT NULL,
  title         TEXT NOT NULL,
  status        TEXT NOT NULL DEFAULT 'pending',
  source        TEXT NOT NULL DEFAULT 'local' CHECK (source IN ('local','cli','mcp','deeplink','fix')),
  tokens_est    INTEGER,
  tokens_actual INTEGER,
  instructions        TEXT NOT NULL DEFAULT '',
  generated_files_json TEXT NOT NULL DEFAULT '[]',
  cost_cents    INTEGER DEFAULT 0,
  started_at    TEXT,
  finished_at   TEXT,
  created_at    TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at    TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (round_id, chunk_no)
);

INSERT INTO tasks_new (id, round_id, chunk_no, title, status, source, tokens_est, tokens_actual,
                       instructions, generated_files_json, cost_cents, started_at, finished_at,
                       created_at, updated_at)
SELECT id, round_id, chunk_no, title, status, source, tokens_est, tokens_actual,
       instructions, generated_files_json, cost_cents, started_at, finished_at,
       created_at, updated_at
FROM tasks;

DROP TABLE tasks;
ALTER TABLE tasks_new RENAME TO tasks;
UPDATE sqlite_sequence SET name = 'tasks' WHERE name = 'tasks_new';
