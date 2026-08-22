-- L11__skills_mcp_attachments.sql · Skills 与 MCP 数据底座（FR-025/026/010/011）
-- 规则：已执行脚本不可修改；schema 演进追加 L12…

-- 本地技能注册表（全局，不挂项目）：文件在 ~/.devpilot/skills/<name>/SKILL.md
CREATE TABLE IF NOT EXISTS skills_local (
  id           INTEGER PRIMARY KEY AUTOINCREMENT,
  name         TEXT    NOT NULL UNIQUE CHECK (name GLOB '[a-z0-9-]*' AND length(name) BETWEEN 1 AND 64),
  display_name TEXT    NOT NULL DEFAULT '',
  description  TEXT    NOT NULL DEFAULT '',
  yaml_path    TEXT    NOT NULL,
  version      TEXT    NOT NULL DEFAULT '0.1.0',
  enabled      INTEGER NOT NULL DEFAULT 1,
  status       TEXT    NOT NULL DEFAULT 'valid' CHECK (status IN ('valid','invalid')),
  status_msg   TEXT    NOT NULL DEFAULT '',
  created_at   TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at   TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_skills_local_enabled ON skills_local(enabled, name);

-- MCP server 管理记录（全局）：transport 固定 stdio（MVP）
CREATE TABLE IF NOT EXISTS mcp_servers (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  name        TEXT    NOT NULL UNIQUE CHECK (length(name) BETWEEN 1 AND 64),
  description TEXT    NOT NULL DEFAULT '',
  transport   TEXT    NOT NULL DEFAULT 'stdio' CHECK (transport IN ('stdio')),
  command     TEXT    NOT NULL,
  args_json   TEXT    NOT NULL DEFAULT '[]',
  env_json    TEXT    NOT NULL DEFAULT '{}',
  status      TEXT    NOT NULL DEFAULT 'installed'
              CHECK (status IN ('installed','running','stopped','error','manual_required')),
  pid         INTEGER,
  last_error  TEXT    NOT NULL DEFAULT '',
  enabled     INTEGER NOT NULL DEFAULT 1,
  restart_count INTEGER NOT NULL DEFAULT 0,
  last_restart_at TEXT,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now')),
  updated_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_mcp_servers_enabled ON mcp_servers(enabled, status);

-- 任务输入附件（截图/线框图，挂项目）：文件在 ~/.devpilot/projects/<id>/attachments/
CREATE TABLE IF NOT EXISTS input_attachments (
  id          INTEGER PRIMARY KEY AUTOINCREMENT,
  project_id  INTEGER NOT NULL REFERENCES projects(id) ON DELETE CASCADE,
  kind        TEXT    NOT NULL DEFAULT 'image' CHECK (kind IN ('image')),
  path        TEXT    NOT NULL,
  source_kb   INTEGER NOT NULL DEFAULT 0,
  created_at  TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_input_attachments_project
  ON input_attachments(project_id, created_at);
