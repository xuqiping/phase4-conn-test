//! 本地 SQLite 存储层（FR-048 持久化底座，`~/.devpilot/devpilot.db`）。
//!
//! 设计要点（plan 坑点表）：
//! - **WAL 模式**：读不阻塞写；
//! - **写串行化**：所有写经 [`Db::write`]（Mutex 即写队列），高频事件写入不互相锁死；
//! - **迁移幂等**：L 版本号（见 [`migrate`]），已执行脚本不可改、重复执行无副作用。

pub mod migrate;
pub mod queue;

use rusqlite::Connection;
use std::path::Path;
use std::sync::{Arc, Mutex};

/// 存储层错误：不外泄路径/堆栈给前端（plan 安全清单），上抛时转大白话错误码。
#[derive(Debug, thiserror::Error)]
pub enum DbError {
    #[error("SQLite 错误: {0}")]
    Sqlite(#[from] rusqlite::Error),
    #[error("IO 错误: {0}")]
    Io(#[from] std::io::Error),
    #[error("写队列不可用（锁中毒）")]
    QueuePoisoned,
}

pub type DbResult<T> = Result<T, DbError>;

/// 本地库句柄：单连接 + Mutex 串行写。`Clone` 廉价（Arc 共享），可安全跨线程传递。
/// WAL 模式下若未来读压力上来，可在此扩只读连接池，调用方无感。
#[derive(Clone)]
pub struct Db {
    pub(crate) conn: Arc<Mutex<Connection>>,
}

impl Db {
    /// 打开（不存在则创建）并应用未执行的迁移。
    pub fn open(path: impl AsRef<Path>) -> DbResult<Self> {
        Self::init(Connection::open(path)?)
    }

    /// 内存库（测试用；journal_mode 退化为 memory）。
    pub fn open_in_memory() -> DbResult<Self> {
        Self::init(Connection::open_in_memory()?)
    }

    fn init(conn: Connection) -> DbResult<Self> {
        conn.pragma_update(None, "journal_mode", "WAL")?;
        conn.pragma_update(None, "foreign_keys", "ON")?;
        conn.pragma_update(None, "busy_timeout", 5000)?;
        migrate::migrate(&conn)?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    /// 只读查询入口（同样走锁；WAL 下后续可换只读连接优化）。
    pub fn read<T>(&self, f: impl FnOnce(&Connection) -> DbResult<T>) -> DbResult<T> {
        let conn = self.conn.lock().map_err(|_| DbError::QueuePoisoned)?;
        f(&conn)
    }
}
