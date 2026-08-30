//! MCP server 管理记录持久化（FR-026 / AC-029）。
//! 状态机：installed → running ⇄ stopped；异常退出 → error；
//! 5 分钟窗口内自动重启超 3 次 → manual_required（转人工）。

use rusqlite::{params, OptionalExtension};
use serde::{Deserialize, Serialize};

use crate::DbResult;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct McpServerRow {
    pub id: i64,
    pub name: String,
    pub description: String,
    pub transport: String,
    pub command: String,
    pub args_json: String,
    pub env_json: String,
    pub status: String,
    pub pid: Option<i64>,
    pub last_error: String,
    pub enabled: bool,
    pub restart_count: i64,
}

/// 新增一条 server 记录（市场安装/手动添加）。名字唯一，冲突报错。
pub fn insert(
    conn: &rusqlite::Connection,
    name: &str,
    description: &str,
    command: &str,
    args_json: &str,
    env_json: &str,
) -> DbResult<i64> {
    conn.execute(
        "INSERT INTO mcp_servers (name, description, command, args_json, env_json)
         VALUES (?1, ?2, ?3, ?4, ?5)",
        params![name, description, command, args_json, env_json],
    )?;
    Ok(conn.last_insert_rowid())
}

/// 按 name 查重（安装前探测）。
pub fn by_name(conn: &rusqlite::Connection, name: &str) -> DbResult<Option<McpServerRow>> {
    conn.query_row(
        "SELECT id, name, description, transport, command, args_json, env_json, status, pid, last_error, enabled, restart_count
         FROM mcp_servers WHERE name = ?1",
        [name],
        row_to_server,
    )
    .optional()
    .map_err(Into::into)
}

pub fn by_id(conn: &rusqlite::Connection, id: i64) -> DbResult<Option<McpServerRow>> {
    conn.query_row(
        "SELECT id, name, description, transport, command, args_json, env_json, status, pid, last_error, enabled, restart_count
         FROM mcp_servers WHERE id = ?1",
        [id],
        row_to_server,
    )
    .optional()
    .map_err(Into::into)
}

pub fn list(conn: &rusqlite::Connection) -> DbResult<Vec<McpServerRow>> {
    let mut stmt = conn.prepare(
        "SELECT id, name, description, transport, command, args_json, env_json, status, pid, last_error, enabled, restart_count
         FROM mcp_servers ORDER BY name",
    )?;
    let rows = stmt.query_map([], row_to_server)?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}

/// 更新运行状态（生命周期回调写这里；AC-029 的 error 态由异常退出写入）。
pub fn set_status(
    conn: &rusqlite::Connection,
    id: i64,
    status: &str,
    pid: Option<i64>,
    last_error: &str,
) -> DbResult<()> {
    conn.execute(
        "UPDATE mcp_servers
         SET status = ?2, pid = ?3,
             last_error = CASE WHEN ?4 = '' THEN last_error ELSE ?4 END,
             updated_at = datetime('now')
         WHERE id = ?1",
        params![id, status, pid, last_error],
    )?;
    Ok(())
}

/// 重启计数 +1（manual_required 判定用；成功稳定后清零）。
pub fn bump_restart(conn: &rusqlite::Connection, id: i64) -> DbResult<i64> {
    conn.execute(
        "UPDATE mcp_servers
         SET restart_count = restart_count + 1, last_restart_at = datetime('now'), updated_at = datetime('now')
         WHERE id = ?1",
        [id],
    )?;
    let count: i64 = conn.query_row(
        "SELECT restart_count FROM mcp_servers WHERE id = ?1",
        [id],
        |r| r.get(0),
    )?;
    Ok(count)
}

pub fn reset_restart_count(conn: &rusqlite::Connection, id: i64) -> DbResult<()> {
    conn.execute(
        "UPDATE mcp_servers SET restart_count = 0, updated_at = datetime('now') WHERE id = ?1",
        [id],
    )?;
    Ok(())
}

/// 启停开关。
pub fn set_enabled(conn: &rusqlite::Connection, id: i64, enabled: bool) -> DbResult<()> {
    conn.execute(
        "UPDATE mcp_servers SET enabled = ?2, updated_at = datetime('now') WHERE id = ?1",
        params![id, enabled],
    )?;
    Ok(())
}

/// 卸载 = 软删（删记录，配置参数随记录保留在审计里由调用方处理）。
pub fn delete(conn: &rusqlite::Connection, id: i64) -> DbResult<()> {
    conn.execute("DELETE FROM mcp_servers WHERE id = ?1", [id])?;
    Ok(())
}

fn row_to_server(r: &rusqlite::Row<'_>) -> rusqlite::Result<McpServerRow> {
    Ok(McpServerRow {
        id: r.get(0)?,
        name: r.get(1)?,
        description: r.get(2)?,
        transport: r.get(3)?,
        command: r.get(4)?,
        args_json: r.get(5)?,
        env_json: r.get(6)?,
        status: r.get(7)?,
        pid: r.get(8)?,
        last_error: r.get(9)?,
        enabled: r.get(10)?,
        restart_count: r.get(11)?,
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::db::Db;

    fn install_fixture(db: &Db) -> i64 {
        db.write(|c| {
            insert(
                c,
                "filesystem",
                "文件系统工具",
                "npx.cmd",
                r#"["-y","mcp-fs"]"#,
                "{}",
            )
        })
        .unwrap()
    }

    #[test]
    fn insert_list_and_unique_name() {
        let db = Db::open_in_memory().expect("建库");
        let id = install_fixture(&db);
        let dup = db.write(|c| insert(c, "filesystem", "重复", "x", "[]", "{}"));
        assert!(dup.is_err(), "同名 server 必须被拒");
        let all = db.read(list).unwrap();
        assert_eq!(all.len(), 1);
        assert_eq!(all[0].id, id);
        assert_eq!(all[0].status, "installed");
    }

    #[test]
    fn status_lifecycle_and_error_kept() {
        let db = Db::open_in_memory().expect("建库");
        let id = install_fixture(&db);
        db.write(|c| set_status(c, id, "running", Some(4321), ""))
            .unwrap();
        // 异常退出（AC-029）：状态 error + 错误信息保留
        db.write(|c| set_status(c, id, "error", None, "进程退出码 1"))
            .unwrap();
        let row = db.read(|c| by_id(c, id)).unwrap().unwrap();
        assert_eq!(row.status, "error");
        assert_eq!(row.last_error, "进程退出码 1");
        // 空错误不清掉历史 last_error
        db.write(|c| set_status(c, id, "stopped", None, ""))
            .unwrap();
        let row = db.read(|c| by_id(c, id)).unwrap().unwrap();
        assert_eq!(row.status, "stopped");
        assert_eq!(row.last_error, "进程退出码 1");
    }

    #[test]
    fn restart_counter_bumps_and_resets() {
        let db = Db::open_in_memory().expect("建库");
        let id = install_fixture(&db);
        assert_eq!(db.write(|c| bump_restart(c, id)).unwrap(), 1);
        assert_eq!(db.write(|c| bump_restart(c, id)).unwrap(), 2);
        db.write(|c| reset_restart_count(c, id)).unwrap();
        let row = db.read(|c| by_id(c, id)).unwrap().unwrap();
        assert_eq!(row.restart_count, 0);
    }

    #[test]
    fn illegal_status_rejected_by_check() {
        let db = Db::open_in_memory().expect("建库");
        let id = install_fixture(&db);
        let bad = db.write(|c| set_status(c, id, "exploded", None, ""));
        assert!(bad.is_err(), "状态枚举外的值必须被 CHECK 拒绝");
    }
}
