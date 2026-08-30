-- P03 Step4：环境画像缓存（FR-005/AC-005）。
CREATE TABLE IF NOT EXISTS env_profiles (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  project_path_hash TEXT NOT NULL,
  lockfile_hash TEXT NOT NULL,
  profile_json TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(project_path_hash, lockfile_hash)
);

CREATE INDEX IF NOT EXISTS idx_env_profile ON env_profiles (project_path_hash, lockfile_hash);
