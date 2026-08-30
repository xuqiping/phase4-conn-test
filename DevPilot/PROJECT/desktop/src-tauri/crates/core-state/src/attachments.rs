//! 任务输入附件持久化（FR-011 / AC-013）：截图/线框图拖入后登记，
//! 文件本体在 ~/.devpilot/projects/<id>/attachments/（由命令层写入）。

use rusqlite::params;
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AttachmentRow {
    pub id: i64,
    pub project_id: i64,
    pub kind: String,
    pub path: String,
    pub source_kb: i64,
    pub created_at: String,
}

/// 登记一条附件。
pub fn insert(
    conn: &rusqlite::Connection,
    project_id: i64,
    kind: &str,
    path: &str,
    source_kb: i64,
) -> DbResult<i64> {
    conn.execute(
        "INSERT INTO input_attachments (project_id, kind, path, source_kb)
         VALUES (?1, ?2, ?3, ?4)",
        params![project_id, kind, path, source_kb],
    )?;
    Ok(conn.last_insert_rowid())
}

/// 某项目的附件清单（新→旧）。
pub fn list(conn: &rusqlite::Connection, project_id: i64) -> DbResult<Vec<AttachmentRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, project_id, kind, path, source_kb, created_at
         FROM input_attachments WHERE project_id = ?1
         ORDER BY id DESC LIMIT 50",
    )?;
    let rows = stmt.query_map([project_id], |r| {
        Ok(AttachmentRow {
            id: r.get(0)?,
            project_id: r.get(1)?,
            kind: r.get(2)?,
            path: r.get(3)?,
            source_kb: r.get(4)?,
            created_at: r.get(5)?,
        })
    })?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

/// 删除一条（chip × 按钮；文件本体由命令层删）。
pub fn delete(conn: &rusqlite::Connection, id: i64) -> DbResult<()> {
    conn.execute("DELETE FROM input_attachments WHERE id = ?1", [id])?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::Db;

    fn project_fixture(db: &Db) -> i64 {
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, workflow_version) VALUES (?1, ?2, ?3)",
                ("demo", "/tmp/demo", "v1.20"),
            )?;
            Ok(())
        })
        .unwrap();
        1
    }

    #[test]
    fn insert_list_delete_roundtrip() {
        let db = Db::open_in_memory().expect("建库");
        let pid = project_fixture(&db);
        let id = db
            .write(|c| insert(c, pid, "image", "/attachments/shot-1.jpg", 890))
            .unwrap();
        db.write(|c| insert(c, pid, "image", "/attachments/shot-2.jpg", 1200))
            .unwrap();
        let all = db.read(|c| list(c, pid)).unwrap();
        assert_eq!(all.len(), 2);
        assert_eq!(all[0].path, "/attachments/shot-2.jpg", "新→旧排序");
        assert_eq!(all[1].source_kb, 890);
        db.write(|c| delete(c, id)).unwrap();
        assert_eq!(db.read(|c| list(c, pid)).unwrap().len(), 1);
    }

    #[test]
    fn kind_check_rejects_non_image() {
        let db = Db::open_in_memory().expect("建库");
        let pid = project_fixture(&db);
        let bad = db.write(|c| insert(c, pid, "video", "/x.mp4", 1));
        assert!(bad.is_err(), "MVP 只允许 image 附件");
    }

    #[test]
    fn cascade_on_project_delete() {
        let db = Db::open_in_memory().expect("建库");
        let pid = project_fixture(&db);
        db.write(|c| insert(c, pid, "image", "/a.png", 10)).unwrap();
        db.write(|c| {
            c.execute("DELETE FROM projects WHERE id = ?1", [pid])?;
            Ok(())
        })
        .unwrap();
        assert!(
            db.read(|c| list(c, pid)).unwrap().is_empty(),
            "项目删除级联清附件"
        );
    }
}
