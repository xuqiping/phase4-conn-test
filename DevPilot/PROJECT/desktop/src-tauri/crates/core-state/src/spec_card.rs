//! 需求确认卡片持久化（FR-031）。
//!
//! 一张卡片 = 一个大白话需求 + 验收标准列表 + 确认状态。

use rusqlite::{params, Connection, Row};
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SpecCardStatus {
    #[default]
    Pending,
    Confirmed,
    Skipped,
}

impl SpecCardStatus {
    fn as_str(&self) -> &'static str {
        match self {
            SpecCardStatus::Pending => "pending",
            SpecCardStatus::Confirmed => "confirmed",
            SpecCardStatus::Skipped => "skipped",
        }
    }
}

#[derive(Debug, Clone, Default, Serialize, Deserialize, PartialEq, Eq)]
pub struct SpecCard {
    pub id: i64,
    pub project_id: i64,
    pub title: String,
    pub detail: String,
    pub ac: Vec<String>,
    pub status: SpecCardStatus,
    pub sort_order: i32,
    pub created_at: String,
    pub updated_at: String,
}

impl SpecCard {
    fn from_row(r: &Row) -> rusqlite::Result<Self> {
        let status: String = r.get(4)?;
        let ac_json: String = r.get(5)?;
        Ok(Self {
            id: r.get(0)?,
            project_id: r.get(1)?,
            title: r.get(2)?,
            detail: r.get(3)?,
            status: match status.as_str() {
                "confirmed" => SpecCardStatus::Confirmed,
                "skipped" => SpecCardStatus::Skipped,
                _ => SpecCardStatus::Pending,
            },
            ac: serde_json::from_str(&ac_json).unwrap_or_default(),
            sort_order: r.get(6)?,
            created_at: r.get(7)?,
            updated_at: r.get(8)?,
        })
    }
}

/// 列出某项目全部需求卡（按 sort_order 排序）。
pub fn list(conn: &Connection, project_id: i64) -> DbResult<Vec<SpecCard>> {
    let mut stmt = conn.prepare(
        "SELECT id, project_id, title, detail, status, ac_json, sort_order, created_at, updated_at
         FROM spec_cards
         WHERE project_id = ?1
         ORDER BY sort_order, id",
    )?;
    let rows = stmt.query_map([project_id], SpecCard::from_row)?;
    rows.collect::<Result<_, _>>().map_err(Into::into)
}

/// 按 id 读取单张卡片。
pub fn get(conn: &Connection, id: i64) -> DbResult<Option<SpecCard>> {
    let mut stmt = conn.prepare(
        "SELECT id, project_id, title, detail, status, ac_json, sort_order, created_at, updated_at
         FROM spec_cards
         WHERE id = ?1",
    )?;
    let mut rows = stmt.query([id])?;
    if let Some(row) = rows.next()? {
        Ok(Some(SpecCard::from_row(row)?))
    } else {
        Ok(None)
    }
}

/// 清空某项目全部需求卡（重新生成报告后使用）。
pub fn clear(conn: &mut Connection, project_id: i64) -> DbResult<()> {
    conn.execute("DELETE FROM spec_cards WHERE project_id = ?1", [project_id])?;
    Ok(())
}

/// 批量插入需求卡（事务内）。
pub fn insert_batch(conn: &mut Connection, project_id: i64, cards: &[SpecCard]) -> DbResult<()> {
    let tx = conn.transaction()?;
    for (i, card) in cards.iter().enumerate() {
        let ac_json = serde_json::to_string(&card.ac).unwrap_or_else(|_| "[]".into());
        tx.execute(
            "INSERT INTO spec_cards
             (project_id, title, detail, ac_json, status, sort_order)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)",
            params![
                project_id,
                card.title,
                card.detail,
                ac_json,
                card.status.as_str(),
                i as i32
            ],
        )?;
    }
    tx.commit()?;
    Ok(())
}

/// 更新单张卡片的内容与状态。
pub fn update(
    conn: &mut Connection,
    id: i64,
    title: Option<&str>,
    detail: Option<&str>,
    ac: Option<&[String]>,
    status: Option<SpecCardStatus>,
) -> DbResult<bool> {
    // 先读原记录
    let existing = get(conn, id)?;
    let existing = match existing {
        Some(e) => e,
        None => return Ok(false),
    };

    let title = title.unwrap_or(&existing.title);
    let detail = detail.unwrap_or(&existing.detail);
    let ac_json = ac
        .map(|a| serde_json::to_string(a).unwrap_or_else(|_| "[]".into()))
        .unwrap_or_else(|| serde_json::to_string(&existing.ac).unwrap_or_else(|_| "[]".into()));
    let status = status.as_ref().unwrap_or(&existing.status).as_str();

    let n = conn.execute(
        "UPDATE spec_cards
         SET title = ?1, detail = ?2, ac_json = ?3, status = ?4, updated_at = datetime('now')
         WHERE id = ?5",
        params![title, detail, ac_json, status, id],
    )?;
    Ok(n > 0)
}

/// 统计某项目 pending 数量。
pub fn count_pending(conn: &Connection, project_id: i64) -> DbResult<i64> {
    let count: i64 = conn.query_row(
        "SELECT COUNT(*) FROM spec_cards
         WHERE project_id = ?1 AND status = 'pending'",
        [project_id],
        |r| r.get(0),
    )?;
    Ok(count)
}

/// 是否所有卡片都已确认或跳过。
pub fn all_resolved(conn: &Connection, project_id: i64) -> DbResult<bool> {
    let count: i64 = conn.query_row(
        "SELECT COUNT(*) FROM spec_cards
         WHERE project_id = ?1 AND status NOT IN ('confirmed','skipped')",
        [project_id],
        |r| r.get(0),
    )?;
    Ok(count == 0)
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
        let card = SpecCard {
            project_id: pid,
            title: "用户登录".into(),
            detail: "支持手机号+验证码登录".into(),
            ac: vec!["验证码 60 秒有效".into()],
            ..Default::default()
        };
        db.write(|c| insert_batch(c, pid, &[card])).unwrap();

        let cards = db.read(|c| list(c, pid)).unwrap();
        assert_eq!(cards.len(), 1);
        assert_eq!(cards[0].title, "用户登录");
    }

    #[test]
    fn update_status_and_all_resolved() {
        let (db, pid) = fixture();
        let card = SpecCard {
            project_id: pid,
            title: "用户登录".into(),
            detail: "...".into(),
            ..Default::default()
        };
        db.write(|c| insert_batch(c, pid, &[card])).unwrap();
        let cards = db.read(|c| list(c, pid)).unwrap();
        let id = cards[0].id;

        assert!(!db.read(|c| all_resolved(c, pid)).unwrap());

        db.write(|c| update(c, id, None, None, None, Some(SpecCardStatus::Confirmed)))
            .unwrap();

        assert!(db.read(|c| all_resolved(c, pid)).unwrap());
    }
}
