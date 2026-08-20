//! 验收/冒烟/安全扫描运行记录（acceptance_runs）持久化。
//! 所有运行只增不改，支持历史回看与联动状态判断。

use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AcceptanceRun {
    pub id: i64,
    pub project_id: i64,
    pub kind: String,
    pub status: String,
    pub started_at: String,
    pub finished_at: Option<String>,
    pub summary_json: String,
}

/// 开始一次运行，返回自增 id。
pub fn start(conn: &rusqlite::Connection, project_id: i64, kind: &str) -> DbResult<i64> {
    conn.execute(
        "INSERT INTO acceptance_runs (project_id, kind, status) VALUES (?1, ?2, 'running')",
        params![project_id, kind],
    )?;
    Ok(conn.last_insert_rowid())
}

/// 结束一次运行并写入结果摘要。
pub fn finish(
    conn: &rusqlite::Connection,
    id: i64,
    status: &str,
    summary_json: &str,
) -> DbResult<()> {
    conn.execute(
        "UPDATE acceptance_runs SET status = ?1, finished_at = datetime('now'), summary_json = ?2
         WHERE id = ?3",
        params![status, summary_json, id],
    )?;
    Ok(())
}

/// 读取某项目某类最新一次运行。
pub fn latest(
    conn: &rusqlite::Connection,
    project_id: i64,
    kind: &str,
) -> DbResult<Option<AcceptanceRun>> {
    conn.query_row(
        "SELECT id, project_id, kind, status, started_at, finished_at, summary_json
         FROM acceptance_runs WHERE project_id = ?1 AND kind = ?2 ORDER BY started_at DESC LIMIT 1",
        params![project_id, kind],
        |r| {
            Ok(AcceptanceRun {
                id: r.get(0)?,
                project_id: r.get(1)?,
                kind: r.get(2)?,
                status: r.get(3)?,
                started_at: r.get(4)?,
                finished_at: r.get(5)?,
                summary_json: r.get(6)?,
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
    fn start_finish_and_latest() {
        let db = Db::open_in_memory().expect("建库");
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, workflow_version) VALUES (?1, ?2, ?3)",
                ("demo", "/tmp/demo", "v1"),
            )?;
            Ok(())
        })
        .unwrap();
        let id = db.write(|c| start(c, 1, "security")).unwrap();
        db.write(|c| finish(c, id, "fail", r#"{"count":3}"#))
            .unwrap();

        let run = db.read(|c| latest(c, 1, "security")).unwrap().unwrap();
        assert_eq!(run.status, "fail");
        assert!(run.summary_json.contains("count"));
        assert!(run.finished_at.is_some());
    }
}
