//! 验收清单持久化（FR-033 / AC-036）。

use crate::DbResult;
use rusqlite::{Connection, OptionalExtension};

#[derive(Debug, Clone)]
pub struct AcceptanceItem {
    pub id: i64,
    pub project_id: i64,
    pub source_file: String,
    pub tc_id: String,
    pub title: String,
    pub steps: String,
    pub expected: String,
    pub method: String,
    pub status: String,
    pub evidence_path: Option<String>,
    pub fix_task_id: Option<i64>,
    pub sort_order: i32,
}

/// 新建验收项时用的输入结构（由 orchestrator parser 转换而来）。
#[derive(Debug, Clone)]
pub struct NewAcceptanceItem {
    pub source_file: String,
    pub tc_id: String,
    pub title: String,
    pub steps: String,
    pub expected: String,
    pub method: String,
    pub sort_order: i32,
}

#[derive(Debug, Clone, Default)]
pub struct AcceptanceStats {
    pub total: i64,
    pub pass: i64,
    pub fail: i64,
    pub na: i64,
    pub pending: i64,
    pub auto: i64,
    pub manual: i64,
}

/// 重新生成清单：事务内删除旧项并插入新项。
pub fn regenerate(c: &Connection, project_id: i64, items: &[NewAcceptanceItem]) -> DbResult<()> {
    let tx = c.unchecked_transaction()?;
    tx.execute(
        "DELETE FROM acceptance_items WHERE project_id = ?1",
        [project_id],
    )?;
    let mut stmt = tx.prepare(
        "INSERT INTO acceptance_items
         (project_id, source_file, tc_id, title, steps, expected, method, sort_order)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
    )?;
    for (i, item) in items.iter().enumerate() {
        stmt.execute((
            project_id,
            &item.source_file,
            &item.tc_id,
            &item.title,
            &item.steps,
            &item.expected,
            &item.method,
            i as i32,
        ))?;
    }
    drop(stmt);
    tx.commit()?;
    Ok(())
}

/// 列出某项目的全部验收项（按 sort_order）。
pub fn list(c: &Connection, project_id: i64) -> DbResult<Vec<AcceptanceItem>> {
    let mut stmt = c.prepare(
        "SELECT id, project_id, source_file, tc_id, title, steps, expected,
                method, status, evidence_path, fix_task_id, sort_order
         FROM acceptance_items
         WHERE project_id = ?1
         ORDER BY sort_order",
    )?;
    let rows = stmt.query_map([project_id], |r| {
        Ok(AcceptanceItem {
            id: r.get(0)?,
            project_id: r.get(1)?,
            source_file: r.get(2)?,
            tc_id: r.get(3)?,
            title: r.get(4)?,
            steps: r.get(5)?,
            expected: r.get(6)?,
            method: r.get(7)?,
            status: r.get(8)?,
            evidence_path: r.get(9)?,
            fix_task_id: r.get(10)?,
            sort_order: r.get(11)?,
        })
    })?;
    rows.collect::<Result<Vec<_>, _>>().map_err(Into::into)
}

fn row_to_item(r: &rusqlite::Row<'_>) -> Result<AcceptanceItem, rusqlite::Error> {
    Ok(AcceptanceItem {
        id: r.get(0)?,
        project_id: r.get(1)?,
        source_file: r.get(2)?,
        tc_id: r.get(3)?,
        title: r.get(4)?,
        steps: r.get(5)?,
        expected: r.get(6)?,
        method: r.get(7)?,
        status: r.get(8)?,
        evidence_path: r.get(9)?,
        fix_task_id: r.get(10)?,
        sort_order: r.get(11)?,
    })
}

/// 按 id 读取单条验收项。
pub fn get(c: &Connection, id: i64) -> DbResult<Option<AcceptanceItem>> {
    c.query_row(
        "SELECT id, project_id, source_file, tc_id, title, steps, expected,
                method, status, evidence_path, fix_task_id, sort_order
         FROM acceptance_items
         WHERE id = ?1",
        [id],
        row_to_item,
    )
    .optional()
    .map_err(Into::into)
}

/// 更新状态与证据路径。
pub fn update_status(
    c: &Connection,
    id: i64,
    status: &str,
    evidence_path: Option<&str>,
) -> DbResult<bool> {
    let n = match evidence_path {
        Some(p) => c.execute(
            "UPDATE acceptance_items SET status = ?1, evidence_path = ?2 WHERE id = ?3",
            (status, p, id),
        )?,
        None => c.execute(
            "UPDATE acceptance_items SET status = ?1 WHERE id = ?2",
            (status, id),
        )?,
    };
    Ok(n > 0)
}

/// 绑定/解绑修复任务。
pub fn set_fix_task(c: &Connection, item_id: i64, fix_task_id: Option<i64>) -> DbResult<bool> {
    let n = c.execute(
        "UPDATE acceptance_items SET fix_task_id = ?1 WHERE id = ?2",
        (fix_task_id, item_id),
    )?;
    Ok(n > 0)
}

/// 按 fix_task_id 查找验收项。
pub fn find_by_fix_task(c: &Connection, fix_task_id: i64) -> DbResult<Option<AcceptanceItem>> {
    c.query_row(
        "SELECT id, project_id, source_file, tc_id, title, steps, expected,
                method, status, evidence_path, fix_task_id, sort_order
         FROM acceptance_items
         WHERE fix_task_id = ?1",
        [fix_task_id],
        |r| {
            Ok(AcceptanceItem {
                id: r.get(0)?,
                project_id: r.get(1)?,
                source_file: r.get(2)?,
                tc_id: r.get(3)?,
                title: r.get(4)?,
                steps: r.get(5)?,
                expected: r.get(6)?,
                method: r.get(7)?,
                status: r.get(8)?,
                evidence_path: r.get(9)?,
                fix_task_id: r.get(10)?,
                sort_order: r.get(11)?,
            })
        },
    )
    .optional()
    .map_err(Into::into)
}

/// 统计某项目的验收项状态。
pub fn stats(c: &Connection, project_id: i64) -> DbResult<AcceptanceStats> {
    let mut s = AcceptanceStats::default();
    let rows: Vec<(String, String, i64)> = {
        let mut stmt = c.prepare(
            "SELECT method, status, COUNT(*)
             FROM acceptance_items
             WHERE project_id = ?1
             GROUP BY method, status",
        )?;
        let mapped = stmt.query_map([project_id], |r| {
            Ok((
                r.get::<_, String>(0)?,
                r.get::<_, String>(1)?,
                r.get::<_, i64>(2)?,
            ))
        })?;
        mapped.collect::<Result<Vec<_>, _>>()?
    };
    for (method, status, count) in rows {
        s.total += count;
        match method.as_str() {
            "auto" => s.auto += count,
            "manual" => s.manual += count,
            _ => {}
        }
        match status.as_str() {
            "pass" => s.pass += count,
            "fail" => s.fail += count,
            "na" => s.na += count,
            _ => s.pending += count,
        }
    }
    Ok(s)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Db;

    fn fixture() -> Db {
        Db::open_in_memory().unwrap()
    }

    #[test]
    fn regenerate_and_list() {
        let db = fixture();
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, scale, workflow_version) VALUES ('p', '/tmp/p', 'L1', '1.20')",
                [],
            )?;
            Ok(())
        })
        .unwrap();
        let pid = 1i64;
        let items = vec![
            NewAcceptanceItem {
                source_file: "a.md".into(),
                tc_id: "TC-01".into(),
                title: "登录".into(),
                steps: "打开页".into(),
                expected: "成功".into(),
                method: "manual".into(),
                sort_order: 0,
            },
            NewAcceptanceItem {
                source_file: "a.md".into(),
                tc_id: "TC-02".into(),
                title: "冒烟".into(),
                steps: "运行".into(),
                expected: "通过".into(),
                method: "auto".into(),
                sort_order: 1,
            },
        ];
        db.write(|c| regenerate(c, pid, &items)).unwrap();
        let rows = db.read(|c| list(c, pid)).unwrap();
        assert_eq!(rows.len(), 2);
        assert_eq!(rows[1].method, "auto");
    }

    #[test]
    fn stats_counts() {
        let db = fixture();
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, scale, workflow_version) VALUES ('p', '/tmp/p', 'L1', '1.20')",
                [],
            )?;
            Ok(())
        })
        .unwrap();
        let pid = 1i64;
        let items = vec![
            NewAcceptanceItem {
                source_file: "a.md".into(),
                tc_id: "TC-01".into(),
                title: "t".into(),
                steps: "".into(),
                expected: "".into(),
                method: "manual".into(),
                sort_order: 0,
            },
            NewAcceptanceItem {
                source_file: "a.md".into(),
                tc_id: "TC-02".into(),
                title: "t".into(),
                steps: "".into(),
                expected: "".into(),
                method: "auto".into(),
                sort_order: 1,
            },
        ];
        db.write(|c| regenerate(c, pid, &items)).unwrap();
        let id = db.read(|c| list(c, pid)).unwrap()[0].id;
        db.write(|c| update_status(c, id, "pass", None)).unwrap();
        let s = db.read(|c| stats(c, pid)).unwrap();
        assert_eq!(s.total, 2);
        assert_eq!(s.pass, 1);
        assert_eq!(s.pending, 1);
        assert_eq!(s.auto, 1);
        assert_eq!(s.manual, 1);
    }
}
