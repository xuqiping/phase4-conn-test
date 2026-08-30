//! 通用审计日志（L12 表）：无任务上下文的操作（MCP 安装/启停、技能全局操作）。
//! 与 task_events 互补：那边挂 task_id，这边挂 source。

use rusqlite::Connection;

#[derive(Debug, Clone)]
pub struct AuditEntry {
    pub id: i64,
    pub source: String,
    pub action: String,
    pub message: String,
    pub created_at: String,
}

/// 记一条审计（source 约束见表 CHECK：skills/mcp/multimodal）。
pub fn insert(c: &Connection, source: &str, action: &str, message: &str) -> rusqlite::Result<i64> {
    c.execute(
        "INSERT INTO audit_log (source, action, message) VALUES (?1, ?2, ?3)",
        (source, action, message),
    )?;
    Ok(c.last_insert_rowid())
}

/// 最近 n 条（管理页/排查用）。
pub fn recent(c: &Connection, n: i64) -> rusqlite::Result<Vec<AuditEntry>> {
    let mut stmt = c.prepare(
        "SELECT id, source, action, message, created_at FROM audit_log
         ORDER BY id DESC LIMIT ?1",
    )?;
    let rows = stmt.query_map([n], |r| {
        Ok(AuditEntry {
            id: r.get(0)?,
            source: r.get(1)?,
            action: r.get(2)?,
            message: r.get(3)?,
            created_at: r.get(4)?,
        })
    })?;
    rows.collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn insert_and_recent_roundtrip() {
        let db = crate::Db::open_in_memory().unwrap();
        let id = db
            .write(|c| insert(c, "mcp", "start", "启动 fetch").map_err(Into::into))
            .unwrap();
        assert!(id > 0);
        let rows = db.read(|c| recent(c, 10).map_err(Into::into)).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].source, "mcp");
        assert_eq!(rows[0].action, "start");
    }
}
