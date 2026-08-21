//! 安全扫描运行记录持久化（FR-040 / AC-044）。
//! security_scans 表只增不改，保留每次扫描结果。

use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SecurityScan {
    pub id: i64,
    pub project_id: i64,
    pub status: String,
    pub findings_json: String,
    pub started_at: String,
    pub finished_at: Option<String>,
}

/// 插入一条扫描结果。返回自增 id。
pub fn insert(
    conn: &rusqlite::Connection,
    project_id: i64,
    status: &str,
    findings_json: &str,
) -> DbResult<i64> {
    conn.execute(
        "INSERT INTO security_scans (project_id, status, findings_json, finished_at)
         VALUES (?1, ?2, ?3, datetime('now'))",
        params![project_id, status, findings_json],
    )?;
    Ok(conn.last_insert_rowid())
}

/// 读取某项目最新一条扫描记录。
pub fn latest(conn: &rusqlite::Connection, project_id: i64) -> DbResult<Option<SecurityScan>> {
    conn.query_row(
        "SELECT id, project_id, status, findings_json, started_at, finished_at
         FROM security_scans WHERE project_id = ?1 ORDER BY id DESC LIMIT 1",
        [project_id],
        |r| {
            Ok(SecurityScan {
                id: r.get(0)?,
                project_id: r.get(1)?,
                status: r.get(2)?,
                findings_json: r.get(3)?,
                started_at: r.get(4)?,
                finished_at: r.get(5)?,
            })
        },
    )
    .optional()
    .map_err(Into::into)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::Db;

    #[test]
    fn insert_and_latest() {
        let db = Db::open_in_memory().expect("建库");
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, workflow_version) VALUES (?1, ?2, ?3)",
                ("demo", "/tmp/demo", "v1"),
            )?;
            Ok(())
        })
        .unwrap();
        let id = db
            .write(|c| insert(c, 1, "fail", r#"[{"severity":"high"}]"#))
            .unwrap();
        assert!(id > 0);
        let scan = db.read(|c| latest(c, 1)).unwrap().unwrap();
        assert_eq!(scan.status, "fail");
        assert!(scan.findings_json.contains("severity"));
    }
}
