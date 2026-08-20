-- L8__build_runtime.sql · 建造期运行时扩展（FR-038/048 底座）
-- 规则：已执行脚本不可修改；schema 演进追加 L9/L10…

-- 任务表扩展：LLM 生成的实现指令、产出文件、成本、起止时间
ALTER TABLE tasks ADD COLUMN instructions        TEXT NOT NULL DEFAULT '';
ALTER TABLE tasks ADD COLUMN generated_files_json TEXT NOT NULL DEFAULT '[]';
ALTER TABLE tasks ADD COLUMN cost_cents          INTEGER DEFAULT 0;
ALTER TABLE tasks ADD COLUMN started_at          TEXT;
ALTER TABLE tasks ADD COLUMN finished_at         TEXT;

-- 任务事件流：runner 结构化事件持久化
CREATE TABLE IF NOT EXISTS task_events (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  task_id    INTEGER NOT NULL REFERENCES tasks(id) ON DELETE CASCADE,
  event_type TEXT    NOT NULL CHECK (event_type IN ('narrative','raw','error','checkpoint')),
  message    TEXT    NOT NULL DEFAULT '',
  created_at TEXT    NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_task_events_task ON task_events(task_id, created_at);

-- 存档点增强：标题与状态
ALTER TABLE checkpoints ADD COLUMN title  TEXT;
ALTER TABLE checkpoints ADD COLUMN status TEXT CHECK (status IN ('success','failed'));
