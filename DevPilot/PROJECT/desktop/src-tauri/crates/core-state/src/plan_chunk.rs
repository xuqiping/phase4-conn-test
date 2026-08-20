//! 施工计划 chunk 持久化（FR-032）。
//!
//! 一个 chunk = 一个可独立完成的小任务单元；审批后按顺序在 `tasks` 表中创建任务。

use rusqlite::{params, Connection, Row};
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum PlanChunkStatus {
    #[default]
    Draft,
    Approved,
    Running,
    Done,
}

impl PlanChunkStatus {
    fn as_str(&self) -> &'static str {
        match self {
            PlanChunkStatus::Draft => "draft",
            PlanChunkStatus::Approved => "approved",
            PlanChunkStatus::Running => "running",
            PlanChunkStatus::Done => "done",
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct PlanChunk {
    pub id: i64,
    pub project_id: i64,
    pub title: String,
    pub goal: String,
    pub estimated_tokens: Option<i64>,
    pub dependencies: Vec<String>,
    pub status: PlanChunkStatus,
    pub sort_order: i32,
    pub created_at: String,
    pub updated_at: String,
}

impl PlanChunk {
    fn from_row(r: &Row) -> rusqlite::Result<Self> {
        let status: String = r.get(4)?;
        let deps_json: String = r.get(5)?;
        Ok(Self {
            id: r.get(0)?,
            project_id: r.get(1)?,
            title: r.get(2)?,
            goal: r.get(3)?,
            status: match status.as_str() {
                "approved" => PlanChunkStatus::Approved,
                "running" => PlanChunkStatus::Running,
                "done" => PlanChunkStatus::Done,
                _ => PlanChunkStatus::Draft,
            },
            dependencies: serde_json::from_str(&deps_json).unwrap_or_default(),
            estimated_tokens: r.get(6)?,
            sort_order: r.get(7)?,
            created_at: r.get(8)?,
            updated_at: r.get(9)?,
        })
    }
}

/// 列出某项目全部 chunk（按 sort_order 排序）。
pub fn list(conn: &Connection, project_id: i64) -> DbResult<Vec<PlanChunk>> {
    let mut stmt = conn.prepare(
        "SELECT id, project_id, title, goal, status, dependencies_json,
                estimated_tokens, sort_order, created_at, updated_at
         FROM plan_chunks
         WHERE project_id = ?1
         ORDER BY sort_order, id",
    )?;
    let rows = stmt.query_map([project_id], PlanChunk::from_row)?;
    rows.collect::<Result<_, _>>().map_err(Into::into)
}

/// 按 id 读取单个 chunk。
pub fn get(conn: &Connection, id: i64) -> DbResult<Option<PlanChunk>> {
    let mut stmt = conn.prepare(
        "SELECT id, project_id, title, goal, status, dependencies_json,
                estimated_tokens, sort_order, created_at, updated_at
         FROM plan_chunks
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(PlanChunk::from_row(row)?))
    } else {
        Ok(None)
    }
}

/// 清空某项目全部 chunk（重新生成计划后使用）。
pub fn clear(conn: &mut Connection, project_id: i64) -> DbResult<()> {
    conn.execute(
        "DELETE FROM plan_chunks WHERE project_id = ?1",
        [project_id],
    )?;
    Ok(())
}

/// 批量插入 chunk（事务内）。
pub fn insert_batch(conn: &mut Connection, project_id: i64, chunks: &[PlanChunk]) -> DbResult<()> {
    let tx = conn.transaction()?;
    for (i, chunk) in chunks.iter().enumerate() {
        let deps_json = serde_json::to_string(&chunk.dependencies).unwrap_or_else(|_| "[]".into());
        tx.execute(
            "INSERT INTO plan_chunks
             (project_id, title, goal, dependencies_json, estimated_tokens, status, sort_order)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            params![
                project_id,
                chunk.title,
                chunk.goal,
                deps_json,
                chunk.estimated_tokens,
                chunk.status.as_str(),
                i as i32
            ],
        )?;
    }
    tx.commit()?;
    Ok(())
}

/// 更新单个 chunk。
pub fn update(
    conn: &mut Connection,
    id: i64,
    title: Option<&str>,
    goal: Option<&str>,
    estimated_tokens: Option<Option<i64>>,
    dependencies: Option<&[String]>,
) -> DbResult<bool> {
    let existing = match get(conn, id)? {
        Some(e) => e,
        None => return Ok(false),
    };

    let title = title.unwrap_or(&existing.title);
    let goal = goal.unwrap_or(&existing.goal);
    let estimated_tokens = estimated_tokens.unwrap_or(existing.estimated_tokens);
    let deps_json = dependencies
        .map(|d| serde_json::to_string(d).unwrap_or_else(|_| "[]".into()))
        .unwrap_or_else(|| {
            serde_json::to_string(&existing.dependencies).unwrap_or_else(|_| "[]".into())
        });

    let n = conn.execute(
        "UPDATE plan_chunks
         SET title = ?1, goal = ?2, dependencies_json = ?3, estimated_tokens = ?4,
             updated_at = datetime('now')
         WHERE id = ?5",
        params![title, goal, deps_json, estimated_tokens, id],
    )?;
    Ok(n > 0)
}

/// 将全部 draft chunk 标记为 approved。
pub fn approve_all(conn: &mut Connection, project_id: i64) -> DbResult<usize> {
    let n = conn.execute(
        "UPDATE plan_chunks
         SET status = 'approved', updated_at = datetime('now')
         WHERE project_id = ?1 AND status = 'draft'",
        [project_id],
    )?;
    Ok(n)
}

/// 撤销审批：把 approved 改回 draft。
pub fn revoke_approval(conn: &mut Connection, project_id: i64) -> DbResult<usize> {
    let n = conn.execute(
        "UPDATE plan_chunks
         SET status = 'draft', updated_at = datetime('now')
         WHERE project_id = ?1 AND status = 'approved'",
        [project_id],
    )?;
    Ok(n)
}

/// 是否还有未审批的 draft chunk。
pub fn has_draft(conn: &Connection, project_id: i64) -> DbResult<bool> {
    let count: i64 = conn.query_row(
        "SELECT COUNT(*) FROM plan_chunks
         WHERE project_id = ?1 AND status = 'draft'",
        [project_id],
        |r| r.get(0),
    )?;
    Ok(count > 0)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Db;

    fn fixture() -> (Db, i64) {
        let db = Db::open_in_memory().unwrap();
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, workflow_version) VALUES (?1, ?2, ?3)",
                ("demo", "/tmp/demo", "v1.20"),
            )?;
            Ok(())
        })
        .unwrap();
        (db, 1)
    }

    #[test]
    fn insert_and_list() {
        let (db, pid) = fixture();
        let chunk = PlanChunk {
            project_id: pid,
            title: "实现登录 API".into(),
            goal: "提供手机号验证码登录".into(),
            estimated_tokens: Some(1200),
            dependencies: vec!["数据库用户表".into()],
            ..Default::default()
        };
        db.write(|c| insert_batch(c, pid, &[chunk])).unwrap();

        let chunks = db.read(|c| list(c, pid)).unwrap();
        assert_eq!(chunks.len(), 1);
        assert_eq!(chunks[0].title, "实现登录 API");
    }

    #[test]
    fn approve_and_revoke() {
        let (db, pid) = fixture();
        let chunk = PlanChunk {
            project_id: pid,
            title: "实现登录 API".into(),
            goal: "...".into(),
            ..Default::default()
        };
        db.write(|c| insert_batch(c, pid, &[chunk])).unwrap();

        assert!(db.read(|c| has_draft(c, pid)).unwrap());
        let n = db.write(|c| approve_all(c, pid)).unwrap();
        assert_eq!(n, 1);
        assert!(!db.read(|c| has_draft(c, pid)).unwrap());

        let n = db.write(|c| revoke_approval(c, pid)).unwrap();
        assert_eq!(n, 1);
        assert!(db.read(|c| has_draft(c, pid)).unwrap());
    }
}
