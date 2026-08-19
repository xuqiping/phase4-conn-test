//! 环境画像缓存读写（FR-005/AC-005）。

use rusqlite::{params, Connection};

use crate::DbResult;

/// 按 (project_path_hash, lockfile_hash) 读缓存。
pub fn get(
    conn: &Connection,
    project_path_hash: &str,
    lockfile_hash: &str,
) -> DbResult<Option<String>> {
    let mut stmt = conn.prepare(
        "SELECT profile_json FROM env_profiles
         WHERE project_path_hash = ?1 AND lockfile_hash = ?2",
    )?;
    let mut rows = stmt.query(params![project_path_hash, lockfile_hash])?;
    if let Some(row) = rows.next()? {
        Ok(Some(row.get(0)?))
    } else {
        Ok(None)
    }
}

/// 写入/更新缓存。
pub fn put(
    conn: &mut Connection,
    project_path_hash: &str,
    lockfile_hash: &str,
    profile_json: &str,
) -> DbResult<()> {
    conn.execute(
        "INSERT INTO env_profiles (project_path_hash, lockfile_hash, profile_json)
         VALUES (?1, ?2, ?3)
         ON CONFLICT(project_path_hash, lockfile_hash) DO UPDATE SET
           profile_json = excluded.profile_json,
           created_at = datetime('now')",
        params![project_path_hash, lockfile_hash, profile_json],
    )?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::Db;

    #[test]
    fn put_and_get() {
        let db = Db::open_in_memory().unwrap();
        db.write(|c| put(c, "h1", "h2", r#"{"stack":"node"}"#))
            .unwrap();
        let got = db.read(|c| get(c, "h1", "h2")).unwrap();
        assert_eq!(got, Some(r#"{"stack":"node"}"#.into()));

        let miss = db.read(|c| get(c, "h1", "h3")).unwrap();
        assert_eq!(miss, None);
    }
}
