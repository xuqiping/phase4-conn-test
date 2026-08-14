//! 转移历史：每次合法转移写一行（plan 运维清单：可回放排查）。

use crate::db::{Db, DbResult};

#[derive(Debug, PartialEq, Eq)]
pub struct HistoryEntry {
    pub from_phase: String,
    pub to_phase: String,
    pub gate: Option<String>,
    pub actor: String,
    pub created_at: String,
}

pub fn record(
    db: &Db,
    project_id: i64,
    from: &str,
    to: &str,
    gate: Option<&str>,
    actor: &str,
) -> DbResult<()> {
    db.write(|c| record_on(c, project_id, from, to, gate, actor).map_err(Into::into))
}

/// 在既有连接上记历史（供调用方在同一 write 闭包/事务内复用，保证原子）。
pub fn record_on(
    c: &rusqlite::Connection,
    project_id: i64,
    from: &str,
    to: &str,
    gate: Option<&str>,
    actor: &str,
) -> rusqlite::Result<()> {
    c.execute(
        "INSERT INTO transition_history (project_id, from_phase, to_phase, gate, actor)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        (project_id, from, to, gate, actor),
    )?;
    Ok(())
}

pub fn list(db: &Db, project_id: i64) -> DbResult<Vec<HistoryEntry>> {
    db.read(|c| {
        let mut stmt = c.prepare(
            "SELECT from_phase, to_phase, gate, actor, created_at
             FROM transition_history WHERE project_id = ?1 ORDER BY id",
        )?;
        let rows = stmt.query_map([project_id], |r| {
            Ok(HistoryEntry {
                from_phase: r.get(0)?,
                to_phase: r.get(1)?,
                gate: r.get(2)?,
                actor: r.get(3)?,
                created_at: r.get(4)?,
            })
        })?;
        Ok(rows.collect::<Result<Vec<_>, _>>()?)
    })
}
