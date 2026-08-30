//! 写队列封装：所有写操作串行执行（plan：「写操作串行队列化」）。
//!
//! 当前实现 = 单连接 Mutex 排队；事务由回调内自行 `conn.transaction()` 控制。
//! 事件高频写入场景（任务日志流）在 P05 如需批量合并，在此层加攒批即可，调用方不改。

use super::{Db, DbError, DbResult};
use rusqlite::Connection;

impl Db {
    /// 串行写入口。回调内所有 SQL 必须参数化（plan 安全清单：防注入）。
    pub fn write<T>(&self, f: impl FnOnce(&mut Connection) -> DbResult<T>) -> DbResult<T> {
        let mut conn = self.conn.lock().map_err(|_| DbError::QueuePoisoned)?;
        f(&mut conn)
    }
}
