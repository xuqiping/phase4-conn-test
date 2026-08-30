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
    /// 联表查询时附带（list_by_project）。
    pub chunk_no: Option<i64>,
    /// 联表查询时附带。
    pub round_id: Option<i64>,
}

fn row_to_checkpoint(r: &rusqlite::Row) -> Result<Checkpoint, rusqlite::Error> {
    Ok(Checkpoint {
        id: r.get(0)?,
        task_id: r.get(1)?,
        git_commit: r.get(2)?,
        snapshot_path: r.get(3)?,
        summary_plain: r.get(4)?,
        title: r.get(5)?,
        status: r.get(6)?,
        created_at: r.get(7)?,
        chunk_no: r.get(8).ok(),
        round_id: r.get(9).ok(),
    })
}

pub fn get(c: &Connection, id: i64) -> DbResult<Option<Checkpoint>> {
    Ok(c.query_row(
        "SELECT id, task_id, git_commit, snapshot_path, summary_plain, title, status, created_at
         FROM checkpoints WHERE id = ?1 LIMIT 1",
        [id],
        row_to_checkpoint,
    )
    .optional()?)
}

pub fn get_by_task(c: &Connection, task_id: i64) -> DbResult<Option<Checkpoint>> {
    Ok(c.query_row(
        "SELECT id, task_id, git_commit, snapshot_path, summary_plain, title, status, created_at
         FROM checkpoints WHERE task_id = ?1 LIMIT 1",
        [task_id],
        row_to_checkpoint,
    )
    .optional()?)
}

/// 列出某项目全部 checkpoint，按 chunk 顺序排。
pub fn list_by_project(c: &Connection, project_id: i64) -> DbResult<Vec<Checkpoint>> {
    let mut stmt = c.prepare(
        "SELECT cp.id, cp.task_id, cp.git_commit, cp.snapshot_path, cp.summary_plain, cp.title, cp.status, cp.created_at, t.chunk_no, t.round_id
         FROM checkpoints cp
         JOIN tasks t ON t.id = cp.task_id
         JOIN rounds r ON r.id = t.round_id
         WHERE r.project_id = ?1
         ORDER BY t.chunk_no",
    )?;
    let rows = stmt.query_map([project_id], row_to_checkpoint)?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

pub fn update_summary(c: &Connection, task_id: i64, summary: &str) -> DbResult<usize> {
    Ok(c.execute(
        "UPDATE checkpoints SET summary_plain = ?1 WHERE task_id = ?2",
        (summary, task_id),
    )?)
}

/// 回滚数据库侧：把当前轮次中 chunk 号大于 checkpoint 对应 task 的下游 tasks 重置为 pending，
/// 并删除它们的 checkpoints（保持 task-checkpoint 一一对应）。
pub fn rollback_downstream(c: &Connection, round_id: i64, chunk_no: i64) -> DbResult<usize> {
    // 先删下游 checkpoints，再重置 tasks。
    c.execute(
        "DELETE FROM checkpoints WHERE task_id IN (
            SELECT id FROM tasks WHERE round_id = ?1 AND chunk_no > ?2
        )",
        (round_id, chunk_no),
    )?;
    let affected = c.execute(
        "UPDATE tasks SET status = 'pending', cost_cents = NULL, finished_at = NULL
         WHERE round_id = ?1 AND chunk_no > ?2",
        (round_id, chunk_no),
    )?;
    Ok(affected)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn schema(c: &Connection) {
        c.execute_batch(
            "CREATE TABLE rounds (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                project_id INTEGER NOT NULL,
                seq INTEGER NOT NULL,
                title TEXT,
                status TEXT
            );
            CREATE TABLE tasks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                round_id INTEGER NOT NULL,
                chunk_no INTEGER NOT NULL,
                title TEXT,
                status TEXT,
                cost_cents INTEGER,
                finished_at TEXT
            );
            CREATE TABLE checkpoints (
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
    }

    #[test]
    fn get_by_task_roundtrip() {
        let c = Connection::open_in_memory().unwrap();
        schema(&c);
        c.execute(
            "INSERT INTO checkpoints (task_id, git_commit, summary_plain) VALUES (7, 'abc', 'plain')",
            [],
        )
        .unwrap();
        let cp = get_by_task(&c, 7).unwrap().unwrap();
        assert_eq!(cp.git_commit.as_deref(), Some("abc"));
        assert_eq!(cp.summary_plain, "plain");
    }

    #[test]
    fn list_by_project_orders_by_chunk() {
        let c = Connection::open_in_memory().unwrap();
        schema(&c);
        c.execute(
            "INSERT INTO rounds (project_id, seq, title) VALUES (1, 1, 'r1')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO tasks (round_id, chunk_no, title, status) VALUES (1, 1, 't1', 'done')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO tasks (round_id, chunk_no, title, status) VALUES (1, 2, 't2', 'done')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO checkpoints (task_id, git_commit) VALUES (1, 'a')",
            [],
        )
        .unwrap();
        c.execute(
            "INSERT INTO checkpoints (task_id, git_commit) VALUES (2, 'b')",
            [],
        )
        .unwrap();

        let rows = list_by_project(&c, 1).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].chunk_no, Some(1));
        assert_eq!(rows[1].chunk_no, Some(2));
    }

    #[test]
    fn rollback_downstream_resets_tasks_and_deletes_checkpoints() {
        let c = Connection::open_in_memory().unwrap();
        schema(&c);
        c.execute(
            "INSERT INTO rounds (project_id, seq, title) VALUES (1, 1, 'r1')",
            [],
        )
        .unwrap();
        for i in 1..=3 {
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title, status) VALUES (1, ?1, ?2, 'done')",
                (i, format!("t{i}")),
            )
            .unwrap();
        }
        for i in 1..=3 {
            c.execute(
                "INSERT INTO checkpoints (task_id, git_commit) VALUES (?1, ?2)",
                (i, format!("c{i}")),
            )
            .unwrap();
        }

        let affected = rollback_downstream(&c, 1, 1).unwrap();
        assert_eq!(affected, 2);

        let statuses: Vec<String> = c
            .prepare("SELECT status FROM tasks ORDER BY chunk_no")
            .unwrap()
            .query_map([], |r| r.get::<_, String>(0))
            .unwrap()
            .collect::<Result<Vec<_>, _>>()
            .unwrap();
        assert_eq!(statuses, vec!["done", "pending", "pending"]);

        let remaining: i64 = c
            .query_row("SELECT COUNT(*) FROM checkpoints", [], |r| r.get(0))
            .unwrap();
        assert_eq!(remaining, 1);
    }
}
