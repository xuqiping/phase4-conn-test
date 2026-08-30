//! task_events 表访问（FR-038 日志透明层底座）。
//! 事件只增不改；前端可回放。

use rusqlite::{Connection, OptionalExtension};
use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum TaskEventType {
    /// 大白话叙事节点（"正在安装依赖…"）
    Narrative,
    /// 原始终端输出
    Raw,
    /// 错误信息（已脱敏）
    Error,
    /// 存档点事件
    Checkpoint,
}

impl TaskEventType {
    pub fn as_str(&self) -> &'static str {
        match self {
            TaskEventType::Narrative => "narrative",
            TaskEventType::Raw => "raw",
            TaskEventType::Error => "error",
            TaskEventType::Checkpoint => "checkpoint",
        }
    }

    pub fn parse(s: &str) -> Option<Self> {
        match s {
            "narrative" => Some(TaskEventType::Narrative),
            "raw" => Some(TaskEventType::Raw),
            "error" => Some(TaskEventType::Error),
            "checkpoint" => Some(TaskEventType::Checkpoint),
            _ => None,
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskEvent {
    pub id: Option<i64>,
    pub task_id: i64,
    pub event_type: TaskEventType,
    pub message: String,
    pub created_at: Option<String>,
}

/// 写入一条事件；返回自增 id。
pub fn insert(
    c: &Connection,
    task_id: i64,
    event_type: TaskEventType,
    message: &str,
) -> Result<i64, rusqlite::Error> {
    c.execute(
        "INSERT INTO task_events (task_id, event_type, message) VALUES (?1, ?2, ?3)",
        (task_id, event_type.as_str(), message),
    )?;
    Ok(c.last_insert_rowid())
}

/// 按时间顺序列出某任务的全部事件。
pub fn list_by_task(c: &Connection, task_id: i64) -> Result<Vec<TaskEvent>, rusqlite::Error> {
    let mut stmt = c.prepare(
        "SELECT id, task_id, event_type, message, created_at FROM task_events
         WHERE task_id = ?1 ORDER BY created_at, id",
    )?;
    let rows = stmt.query_map([task_id], |r| {
        Ok(TaskEvent {
            id: r.get(0)?,
            task_id: r.get(1)?,
            event_type: TaskEventType::parse(&r.get::<_, String>(2)?).unwrap_or(TaskEventType::Raw),
            message: r.get(3)?,
            created_at: r.get(4)?,
        })
    })?;
    rows.collect()
}

/// 读取单条事件。
pub fn get(c: &Connection, id: i64) -> Result<Option<TaskEvent>, rusqlite::Error> {
    c.query_row(
        "SELECT id, task_id, event_type, message, created_at FROM task_events WHERE id = ?1",
        [id],
        |r| {
            Ok(TaskEvent {
                id: r.get(0)?,
                task_id: r.get(1)?,
                event_type: TaskEventType::parse(&r.get::<_, String>(2)?)
                    .unwrap_or(TaskEventType::Raw),
                message: r.get(3)?,
                created_at: r.get(4)?,
            })
        },
    )
    .optional()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn in_mem() -> Connection {
        let c = Connection::open_in_memory().unwrap();
        c.execute_batch(
            "CREATE TABLE task_events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                task_id INTEGER NOT NULL,
                event_type TEXT NOT NULL,
                message TEXT NOT NULL DEFAULT '',
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );",
        )
        .unwrap();
        c
    }

    #[test]
    fn insert_and_list_roundtrip() {
        let c = in_mem();
        insert(&c, 7, TaskEventType::Narrative, "开始安装").unwrap();
        insert(&c, 7, TaskEventType::Raw, "npm install").unwrap();
        insert(&c, 8, TaskEventType::Error, "失败").unwrap();

        let rows = list_by_task(&c, 7).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[0].event_type, TaskEventType::Narrative);
        assert_eq!(rows[1].event_type, TaskEventType::Raw);
    }

    #[test]
    fn get_returns_event() {
        let c = in_mem();
        let id = insert(&c, 3, TaskEventType::Checkpoint, "commit-abc").unwrap();
        let ev = get(&c, id).unwrap().unwrap();
        assert_eq!(ev.task_id, 3);
        assert_eq!(ev.event_type, TaskEventType::Checkpoint);
        assert_eq!(ev.message, "commit-abc");
    }
}
