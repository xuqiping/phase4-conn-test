-- L12__audit_log.sql · 通用审计日志（P07 Phase4 修复：MCP 安装/启停/重启/卸载审计）
-- task_events 挂 task_id（NOT NULL），MCP/技能等无任务上下文的操作进这张全局表。
-- 规则：已执行脚本不可修改；schema 演进追加 L13…
CREATE TABLE IF NOT EXISTS audit_log (
  id         INTEGER PRIMARY KEY AUTOINCREMENT,
  source     TEXT NOT NULL CHECK (source IN ('skills','mcp','multimodal')),
  action     TEXT NOT NULL DEFAULT '',
  message    TEXT NOT NULL DEFAULT '',
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_audit_log_time ON audit_log(created_at, source);
