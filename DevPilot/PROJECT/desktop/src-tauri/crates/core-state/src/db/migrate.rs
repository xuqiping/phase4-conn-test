//! L 版本号迁移器：已执行版本跳过，未执行按序应用（对齐 specs/db_schema.md §2 清单）。
//! 铁律同款规则：已执行的 L 脚本不可修改，schema 演进只能追加新版本。

use super::DbResult;
use rusqlite::Connection;

struct Migration {
    version: &'static str,
    sql: &'static str,
}

/// 迁移清单（追加式，顺序即执行顺序）。
const MIGRATIONS: &[Migration] = &[Migration {
    version: "L1",
    sql: include_str!("../../migrations/L1__init.sql"),
}];

/// 应用所有未执行的迁移。重复调用安全（幂等）。
pub fn migrate(conn: &Connection) -> DbResult<()> {
    conn.execute_batch(
        "CREATE TABLE IF NOT EXISTS schema_migrations (
            version    TEXT PRIMARY KEY,
            applied_at TEXT NOT NULL DEFAULT (datetime('now'))
        );",
    )?;
    for m in MIGRATIONS {
        let applied: bool = conn.query_row(
            "SELECT EXISTS(SELECT 1 FROM schema_migrations WHERE version = ?1)",
            [m.version],
            |r| r.get(0),
        )?;
        if applied {
            continue;
        }
        // unchecked_transaction：&Connection 上开事务（本函数不持有 &mut）
        let tx = conn.unchecked_transaction()?;
        tx.execute_batch(m.sql)?;
        tx.execute(
            "INSERT INTO schema_migrations (version) VALUES (?1)",
            [m.version],
        )?;
        tx.commit()?;
    }
    Ok(())
}

/// 已应用版本清单（诊断导出用）。
pub fn applied_versions(conn: &Connection) -> DbResult<Vec<String>> {
    let mut stmt = conn.prepare("SELECT version FROM schema_migrations ORDER BY version")?;
    let rows = stmt.query_map([], |r| r.get(0))?;
    Ok(rows.collect::<Result<Vec<_>, _>>()?)
}
