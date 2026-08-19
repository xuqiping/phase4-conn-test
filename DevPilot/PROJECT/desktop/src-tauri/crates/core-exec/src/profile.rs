// 环境画像缓存：本地 SQLite 存取，命中则跳过文件扫描。
// 对应 FR-005/AC-005。

use std::path::Path;

use serde_json;

use crate::probe::{hash_lockfile, hash_path, EnvProfile};

/// 从缓存读取环境画像；未命中返回 None。
pub fn get_cached(
    conn: &rusqlite::Connection,
    project_dir: &Path,
) -> Result<Option<EnvProfile>, Box<dyn std::error::Error>> {
    let path_hash = hash_path(project_dir);
    let lock_hash = hash_lockfile(project_dir);
    if let Some(json) = core_state::env_profile::get(conn, &path_hash, &lock_hash)? {
        let p: EnvProfile = serde_json::from_str(&json)?;
        return Ok(Some(p));
    }
    Ok(None)
}

/// 探测并把结果写入缓存。
pub fn probe_and_cache(
    conn: &mut rusqlite::Connection,
    project_dir: &Path,
) -> Result<EnvProfile, Box<dyn std::error::Error>> {
    let profile = crate::probe::probe(project_dir);
    let json = serde_json::to_string(&profile)?;
    core_state::env_profile::put(
        conn,
        &hash_path(project_dir),
        &hash_lockfile(project_dir),
        &json,
    )?;
    Ok(profile)
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_state::Db;
    use std::fs;
    use tempfile::TempDir;

    #[test]
    fn cache_hit_skips_probe() {
        let tmp = TempDir::new().unwrap();
        fs::write(tmp.path().join("package.json"), "{}").unwrap();
        // 用 lockfile 稳定指纹：删 package.json 后 hash 仍以 lockfile 为准
        fs::write(
            tmp.path().join("package-lock.json"),
            "{\"lockfileVersion\":3}",
        )
        .unwrap();

        let db = Db::open_in_memory().unwrap();
        let p1 = db
            .write(|c| {
                probe_and_cache(c, tmp.path())
                    .map_err(|e| core_state::DbError::Io(std::io::Error::other(e.to_string())))
            })
            .unwrap();
        assert!(p1.stacks.contains(&crate::probe::Stack::Node));

        // 删 package.json 后，lockfile 指纹仍命中缓存
        fs::remove_file(tmp.path().join("package.json")).unwrap();
        let p2 = db
            .read(|c| {
                get_cached(c, tmp.path())
                    .map_err(|e| core_state::DbError::Io(std::io::Error::other(e.to_string())))
            })
            .unwrap()
            .unwrap();
        assert!(p2.stacks.contains(&crate::probe::Stack::Node));
    }
}
