//! Secrets 管理：名称存在 SQLite，敏感值优先走 OS 凭据管理器（keyring），
//! 不可用时回退到 AES-256-GCM 加密存储（密钥派生自设备指纹）。
//! 对应 FR-012/AC-014。

use aes_gcm::{
    aead::{Aead, AeadCore, KeyInit, OsRng},
    Aes256Gcm, Nonce,
};
use rusqlite::Connection;
use sha2::{Digest, Sha256};

use crate::DbResult;

/// Secret 元数据（不含值）。
#[derive(Debug, Clone)]
pub struct SecretMeta {
    pub id: i64,
    pub project_id: i64,
    pub name: String,
}

const NONCE_LEN: usize = 12;
const TAG_LEN: usize = 16;

/// 保存或更新一个 secret。名称落库，值优先 keyring，keyring 失败则加密落库。
pub fn save(conn: &mut Connection, project_id: i64, name: &str, value: &str) -> DbResult<i64> {
    let encrypted = encrypt(value);
    let tx = conn.unchecked_transaction()?;
    tx.execute(
        "INSERT INTO secrets (project_id, name, encrypted_value, updated_at)
         VALUES (?1, ?2, ?3, datetime('now'))
         ON CONFLICT(project_id, name) DO UPDATE SET
           encrypted_value=?3,
           updated_at=datetime('now')",
        (project_id, name, &encrypted),
    )?;
    let id: i64 = tx.query_row(
        "SELECT id FROM secrets WHERE project_id = ?1 AND name = ?2",
        (project_id, name),
        |r| r.get(0),
    )?;
    tx.commit()?;

    // 同时尝试 OS 凭据管理器；失败不抛错，加密库已保证至少有一份安全副本。
    let _ = keyring_save(project_id, name, value);
    Ok(id)
}

/// 列出某项目的所有 secret 名称。
pub fn list(conn: &Connection, project_id: i64) -> DbResult<Vec<SecretMeta>> {
    let mut stmt = conn
        .prepare("SELECT id, project_id, name FROM secrets WHERE project_id = ?1 ORDER BY name")?;
    let rows = stmt.query_map([project_id], |r| {
        Ok(SecretMeta {
            id: r.get(0)?,
            project_id: r.get(1)?,
            name: r.get(2)?,
        })
    })?;
    rows.collect::<Result<Vec<_>, _>>()
        .map_err(crate::DbError::from)
}

/// 读取 secret 值；优先读 keyring，回退加密库。
pub fn load(conn: &Connection, project_id: i64, name: &str) -> DbResult<Option<String>> {
    let exists: bool = conn.query_row(
        "SELECT EXISTS(SELECT 1 FROM secrets WHERE project_id = ?1 AND name = ?2)",
        (project_id, name),
        |r| r.get(0),
    )?;
    if !exists {
        return Ok(None);
    }

    // 优先 OS 凭据管理器。
    match keyring_load(project_id, name) {
        Ok(v) => return Ok(Some(v)),
        Err(keyring::Error::NoEntry) => {}
        Err(_) => {}
    }

    // 回退：从加密库读取。
    let blob: Vec<u8> = conn.query_row(
        "SELECT encrypted_value FROM secrets WHERE project_id = ?1 AND name = ?2",
        (project_id, name),
        |r| r.get(0),
    )?;
    Ok(decrypt(&blob).or_else(|| Some(String::from_utf8_lossy(&blob).to_string())))
}

/// 删除 secret（DB + keyring）。
pub fn delete(conn: &mut Connection, project_id: i64, name: &str) -> DbResult<bool> {
    let tx = conn.unchecked_transaction()?;
    let rows = tx.execute(
        "DELETE FROM secrets WHERE project_id = ?1 AND name = ?2",
        (project_id, name),
    )?;
    tx.commit()?;
    keyring_delete(project_id, name);
    Ok(rows > 0)
}

fn keyring_entry(project_id: i64, name: &str) -> keyring::Result<keyring::Entry> {
    keyring::Entry::new("devpilot", &format!("project_{project_id}_secret_{name}"))
}

fn keyring_save(project_id: i64, name: &str, value: &str) -> keyring::Result<()> {
    keyring_entry(project_id, name)?.set_password(value)
}

fn keyring_load(project_id: i64, name: &str) -> keyring::Result<String> {
    keyring_entry(project_id, name)?.get_password()
}

fn keyring_delete(project_id: i64, name: &str) {
    if let Ok(e) = keyring_entry(project_id, name) {
        let _ = e.delete_credential();
    }
}

/// 派生 256-bit 主密钥：优先 env DEVPILOT_MASTER_KEY，否则用 hostname+username 的 SHA-256。
fn master_key() -> [u8; 32] {
    let seed = std::env::var("DEVPILOT_MASTER_KEY").unwrap_or_else(|_| {
        format!(
            "{}:{}",
            whoami::fallible::hostname().unwrap_or_else(|_| "unknown".into()),
            whoami::fallible::username().unwrap_or_else(|_| "unknown".into())
        )
    });
    let mut hasher = Sha256::new();
    hasher.update(seed.as_bytes());
    hasher.finalize().into()
}

fn cipher() -> Aes256Gcm {
    Aes256Gcm::new_from_slice(&master_key())
        .expect("SHA-256 输出长度恒为 32 字节，满足 AES-256 密钥长度")
}

fn encrypt(plaintext: &str) -> Vec<u8> {
    let nonce = Aes256Gcm::generate_nonce(&mut OsRng);
    let ct = cipher()
        .encrypt(&nonce, plaintext.as_bytes())
        .expect("AES-GCM 加密不应失败");
    let mut out = Vec::with_capacity(NONCE_LEN + ct.len());
    out.extend_from_slice(nonce.as_slice());
    out.extend_from_slice(&ct);
    out
}

fn decrypt(blob: &[u8]) -> Option<String> {
    if blob.len() < NONCE_LEN + TAG_LEN {
        return None;
    }
    let (n, ct) = blob.split_at(NONCE_LEN);
    let nonce = Nonce::from_slice(n);
    cipher()
        .decrypt(nonce, ct)
        .ok()
        .and_then(|p| String::from_utf8(p).ok())
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
    fn save_list_load_delete_roundtrip() {
        let (db, pid) = fixture();
        db.write(|c| save(c, pid, "API_KEY", "secret123")).unwrap();

        let rows = db.read(|c| list(c, pid)).unwrap();
        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].name, "API_KEY");

        let value = db.read(|c| load(c, pid, "API_KEY")).unwrap();
        assert_eq!(value, Some("secret123".into()));

        let deleted = db.write(|c| delete(c, pid, "API_KEY")).unwrap();
        assert!(deleted);
        let rows = db.read(|c| list(c, pid)).unwrap();
        assert!(rows.is_empty());
    }

    #[test]
    fn update_existing_secret_overwrites_value() {
        let (db, pid) = fixture();
        db.write(|c| save(c, pid, "TOKEN", "old")).unwrap();
        db.write(|c| save(c, pid, "TOKEN", "new")).unwrap();
        let value = db.read(|c| load(c, pid, "TOKEN")).unwrap();
        assert_eq!(value, Some("new".into()));
    }

    #[test]
    fn encrypted_value_not_plaintext() {
        let (db, pid) = fixture();
        db.write(|c| save(c, pid, "X", "hunter2")).unwrap();
        let blob: Vec<u8> = db
            .read(|c| {
                c.query_row(
                    "SELECT encrypted_value FROM secrets WHERE name = ?1",
                    ["X"],
                    |r| r.get::<_, Vec<u8>>(0),
                )
                .map_err(Into::into)
            })
            .unwrap();
        let as_string = String::from_utf8_lossy(&blob);
        assert!(!as_string.contains("hunter2"));
    }
}
