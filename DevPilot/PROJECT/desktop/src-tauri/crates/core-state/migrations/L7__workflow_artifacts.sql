-- L7__workflow_artifacts.sql · 工作流主链产物表（FR-030/031/032/008 底座）
-- 规则：已执行脚本不可修改；schema 演进追加 L8/L9…

CREATE TABLE IF NOT EXISTS agent_configs (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL UNIQUE REFERENCES projects(id) ON DELETE CASCADE,
  fields_json TEXT    NOT NULL DEFAULT '{}',
  created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_agent_configs_project ON agent_configs(project_id);

CREATE TABLE IF NOT EXISTS spec_cards (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  title       TEXT    NOT NULL,
  detail      TEXT    NOT NULL DEFAULT '',
  ac_json     TEXT    NOT NULL DEFAULT '[]',
  status      TEXT    NOT NULL DEFAULT 'pending' CHECK (status IN ('pending','confirmed','skipped')),
  sort_order  INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_spec_cards_project_status ON spec_cards(project_id, status);
CREATE INDEX IF NOT EXISTS idx_spec_cards_sort         ON spec_cards(project_id, sort_order);

CREATE TABLE IF NOT EXISTS plan_chunks (
  id                INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id        INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  title             TEXT    NOT NULL,
  goal              TEXT    NOT NULL DEFAULT '',
  estimated_tokens  INTEGER,
  dependencies_json TEXT    NOT NULL DEFAULT '[]',
  status            TEXT    NOT NULL DEFAULT 'draft' CHECK (status IN ('draft','approved','running','done')),
  sort_order        INTEGER NOT NULL DEFAULT 0,
  created_at        TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at        TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_plan_chunks_project_status ON plan_chunks(project_id, status);
CREATE INDEX IF NOT EXISTS idx_plan_chunks_sort           ON plan_chunks(project_id, sort_order);
