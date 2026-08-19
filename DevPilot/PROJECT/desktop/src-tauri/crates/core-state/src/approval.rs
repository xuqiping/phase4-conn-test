//! 审批事件持久化（FR-009 两档审批模式）。

use rusqlite::{params, Connection, Row};

use crate::DbResult;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingApproval {
    pub id: i64,
    pub project_id: i64,
    pub task_id: Option<i64>,
    pub kind: String,
    pub title: String,
    pub detail: String,
    pub risk_level: String,
    pub decision: Option<String>,
    pub created_at: String,
    pub resolved_at: Option<String>,
}

impl PendingApproval {
    fn from_row(r: &Row) -> rusqlite::Result<Self> {
        Ok(Self {
            id: r.get(0)?,
            project_id: r.get(1)?,
            task_id: r.get(2)?,
            kind: r.get(3)?,
            title: r.get(4)?,
            detail: r.get(5)?,
            risk_level: r.get(6)?,
            decision: r.get(7)?,
            created_at: r.get(8)?,
            resolved_at: r.get(9)?,
        })
    }
}

/// 创建一条待审批记录。
pub fn create(
    conn: &Connection,
    project_id: i64,
    task_id: Option<i64>,
    kind: &str,
    title: &str,
    detail: &str,
    risk_level: &str,
) -> DbResult<i64> {
    conn.execute(
        "INSERT INTO pending_approvals (project_id, task_id, kind, title, detail, risk_level)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
        params![project_id, task_id, kind, title, detail, risk_level],
    )?;
    Ok(conn.last_insert_rowid())
}

/// 列出某项目下未解决的审批。
pub fn list_unresolved(conn: &Connection, project_id: i64) -> DbResult<Vec<PendingApproval>> {
    let mut stmt = conn.prepare(
        "SELECT id, project_id, task_id, kind, title, detail, risk_level, decision, created_at, resolved_at
         FROM pending_approvals
         WHERE project_id = ?1 AND resolved_at IS NULL
         ORDER BY created_at",
    )?;
    let rows = stmt.query_map([project_id], PendingApproval::from_row)?;
    rows.collect::<Result<_, _>>().map_err(Into::into)
}

/// 提交审批决定。
pub fn resolve(conn: &Connection, id: i64, allow: bool) -> DbResult<bool> {
    let decision = if allow { "allow" } else { "deny" };
    let n = conn.execute(
        "UPDATE pending_approvals
         SET decision = ?1, resolved_at = datetime('now')
         WHERE id = ?2 AND resolved_at IS NULL",
        params![decision, id],
    )?;
    Ok(n > 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Db;

    #[test]
    fn create_and_resolve() {
        let db = Db::open_in_memory().unwrap();
        let id = db
            .write(|c| {
                create(
                    c,
                    1,
                    Some(2),
                    "command",
                    "执行 npm test",
                    "项目根目录",
                    "dangerous",
                )
            })
            .unwrap();
        assert!(id > 0);

        let unresolved = db.read(|c| list_unresolved(c, 1)).unwrap();
        assert_eq!(unresolved.len(), 1);

        let ok = db.write(|c| resolve(c, id, true)).unwrap();
        assert!(ok);

        let unresolved = db.read(|c| list_unresolved(c, 1)).unwrap();
        assert!(unresolved.is_empty());
    }
}
