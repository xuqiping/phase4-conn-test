//! checkpoints 表访问（FR-037 存档点底座）。
//! 只增不改；task 与 checkpoint 一一对应。

use rusqlite::{Connection, OptionalExtension};

use crate::DbResult;

#[derive(Debug, Clone)]
pub struct Checkpoint {
    pub id: i64,
    pub task_id: i64,
    pub git_commit: Option<String>,
    pub snapshot_path: Option<String>,
    pub summary_plain: String,
    pub title: Option<String>,
    pub status: Option<String>,
    pub created_at: String,
}

pub fn get_by_task(c: &Connection, task_id: i64) -> DbResult<Option<Checkpoint>> {
    Ok(c.query_row(
        "SELECT id, task_id, git_commit, snapshot_path, summary_plain, title, status, created_at
         FROM checkpoints WHERE task_id = ?1 LIMIT 1",
        [task_id],
        |r| {
            Ok(Checkpoint {
                id: r.get(0)?,
                task_id: r.get(1)?,
                git_commit: r.get(2)?,
                snapshot_path: r.get(3)?,
                summary_plain: r.get(4)?,
                title: r.get(5)?,
                status: r.get(6)?,
                created_at: r.get(7)?,
            })
        },
    )
    .optional()?)
}

pub fn update_summary(c: &Connection, task_id: i64, summary: &str) -> DbResult<usize> {
    Ok(c.execute(
        "UPDATE checkpoints SET summary_plain = ?1 WHERE task_id = ?2",
        (summary, task_id),
    )?)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn get_by_task_roundtrip() {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(
            "CREATE TABLE checkpoints (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                git_commit TEXT,
                snapshot_path TEXT,
                summary_plain TEXT NOT NULL DEFAULT '',
                title TEXT,
                status TEXT,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );",
        )
        .unwrap();
        c.execute(
            "INSERT INTO checkpoints (task_id, git_commit, summary_plain) VALUES (7, 'abc', 'plain')",
            [],
        )
        .unwrap();
        let cp = get_by_task(&c, 7).unwrap().unwrap();
        assert_eq!(cp.git_commit.as_deref(), Some("abc"));
        assert_eq!(cp.summary_plain, "plain");
    }
}
