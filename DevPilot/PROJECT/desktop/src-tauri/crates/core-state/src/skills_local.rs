//! 本地技能注册表持久化（FR-025 / AC-028）。
//! skills_local 全局共享（不挂项目），文件本体在 ~/.devpilot/skills/<name>/SKILL.md。

use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SkillRow {
    pub id: i64,
    pub name: String,
    pub display_name: String,
    pub description: String,
    pub yaml_path: String,
    pub version: String,
    pub enabled: bool,
    pub status: String,
    pub status_msg: String,
}

/// upsert 入参（字段多，收成一个结构体）。
#[derive(Debug, Clone)]
pub struct SkillUpsert<'a> {
    pub name: &'a str,
    pub display_name: &'a str,
    pub description: &'a str,
    pub yaml_path: &'a str,
    pub version: &'a str,
    pub status: &'a str,
    pub status_msg: &'a str,
}

/// 新增或按 name 更新一条技能记录（扫描注册用，幂等）。返回 id。
pub fn upsert(conn: &rusqlite::Connection, s: &SkillUpsert<'_>) -> DbResult<i64> {
    conn.execute(
        "INSERT INTO skills_local (name, display_name, description, yaml_path, version, status, status_msg, updated_at)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, datetime('now'))
         ON CONFLICT(name) DO UPDATE SET
           display_name = excluded.display_name,
           description  = excluded.description,
           yaml_path    = excluded.yaml_path,
           version      = excluded.version,
           status       = excluded.status,
           status_msg   = excluded.status_msg,
           updated_at   = datetime('now')",
        params![s.name, s.display_name, s.description, s.yaml_path, s.version, s.status, s.status_msg],
    )?;
    Ok(conn.last_insert_rowid())
}

/// 全量技能（enabled 过滤可选），按 name 排序。
pub fn list(conn: &rusqlite::Connection, only_enabled: bool) -> DbResult<Vec<SkillRow>> {
    let sql = if only_enabled {
        "SELECT id, name, display_name, description, yaml_path, version, enabled, status, status_msg
         FROM skills_local WHERE enabled = 1 ORDER BY name"
    } else {
        "SELECT id, name, display_name, description, yaml_path, version, enabled, status, status_msg
         FROM skills_local ORDER BY name"
    };
    let mut stmt = conn.prepare(sql)?;
    let rows = stmt.query_map([], row_to_skill)?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

/// 按 name 精确查（斜杠调用的入口校验）。
pub fn by_name(conn: &rusqlite::Connection, name: &str) -> DbResult<Option<SkillRow>> {
    conn.query_row(
        "SELECT id, name, display_name, description, yaml_path, version, enabled, status, status_msg
         FROM skills_local WHERE name = ?1",
        [name],
        row_to_skill,
    )
    .optional()
    .map_err(Into::into)
}

/// 启停技能（联动：禁用即时从斜杠候选消失）。
pub fn set_enabled(conn: &rusqlite::Connection, id: i64, enabled: bool) -> DbResult<()> {
    conn.execute(
        "UPDATE skills_local SET enabled = ?2, updated_at = datetime('now') WHERE id = ?1",
        params![id, enabled],
    )?;
    Ok(())
}

/// 软删（文件本体由 core-skills 移入 .trash/，这里只清记录）。
pub fn delete(conn: &rusqlite::Connection, id: i64) -> DbResult<()> {
    conn.execute("DELETE FROM skills_local WHERE id = ?1", [id])?;
    Ok(())
}

fn row_to_skill(r: &rusqlite::Row<'_>) -> rusqlite::Result<SkillRow> {
    Ok(SkillRow {
        id: r.get(0)?,
        name: r.get(1)?,
        display_name: r.get(2)?,
        description: r.get(3)?,
        yaml_path: r.get(4)?,
        version: r.get(5)?,
        enabled: r.get(6)?,
        status: r.get(7)?,
        status_msg: r.get(8)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::Db;

    fn up<'a>(
        s: &'a SkillUpsert<'_>,
    ) -> impl FnOnce(&mut rusqlite::Connection) -> DbResult<i64> + 'a {
        move |c| upsert(c, s)
    }

    #[test]
    fn upsert_is_idempotent_and_lists() {
        let db = Db::open_in_memory().expect("建库");
        let id1 = db
            .write(up(&SkillUpsert {
                name: "release-check",
                display_name: "发版检查",
                description: "上线前检查",
                yaml_path: "/skills/release-check/SKILL.md",
                version: "0.1.0",
                status: "valid",
                status_msg: "",
            }))
            .unwrap();
        let id2 = db
            .write(up(&SkillUpsert {
                name: "release-check",
                display_name: "发版检查2",
                description: "更新描述",
                yaml_path: "/skills/release-check/SKILL.md",
                version: "0.2.0",
                status: "valid",
                status_msg: "",
            }))
            .unwrap();
        assert_eq!(id1, id2, "同名 upsert 不重复注册");
        let all = db.read(|c| list(c, false)).unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].version, "0.2.0");
        assert_eq!(all[0].display_name, "发版检查2");
    }

    #[test]
    fn enabled_filter_and_by_name_and_delete() {
        let db = Db::open_in_memory().expect("建库");
        let id = db
            .write(up(&SkillUpsert {
                name: "daily-report",
                display_name: "日报",
                description: "生成日报",
                yaml_path: "/skills/daily-report/SKILL.md",
                version: "0.1.0",
                status: "valid",
                status_msg: "",
            }))
            .unwrap();
        // 非法名字必须被 CHECK 拒绝（安全清单：输入校验）
        let bad = db.write(up(&SkillUpsert {
            name: "Bad_Name!",
            display_name: "x",
            description: "y",
            yaml_path: "z",
            version: "0.1.0",
            status: "valid",
            status_msg: "",
        }));
        assert!(bad.is_err(), "技能名只允许 [a-z0-9-]");
        db.write(|c| set_enabled(c, id, false)).unwrap();
        let enabled = db.read(|c| list(c, true)).unwrap();
        assert!(enabled.is_empty(), "禁用后不在候选列表");
        let row = db.read(|c| by_name(c, "daily-report")).unwrap().unwrap();
        assert!(!row.enabled);
        db.write(|c| delete(c, id)).unwrap();
        assert!(db.read(|c| by_name(c, "daily-report")).unwrap().is_none());
    }

    #[test]
    fn invalid_status_preserved_with_msg() {
        let db = Db::open_in_memory().expect("建库");
        db.write(up(&SkillUpsert {
            name: "broken",
            display_name: "坏技能",
            description: "",
            yaml_path: "/skills/broken/SKILL.md",
            version: "0.1.0",
            status: "invalid",
            status_msg: "frontmatter 不是合法 YAML",
        }))
        .unwrap();
        let row = db.read(|c| by_name(c, "broken")).unwrap().unwrap();
        assert_eq!(row.status, "invalid");
        assert!(row.status_msg.contains("YAML"));
    }
}
