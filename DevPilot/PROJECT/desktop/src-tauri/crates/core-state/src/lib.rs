//! core-state：阶段状态机（YAML 驱动）+ 项目/任务/轮次持久化。
//! 对应 FR-029/046/047/048。Step 4 落 SQLite 存储层（`db` 模块），Step 6 落状态机引擎（`machine` 模块）。

pub mod approval;
pub mod db;
pub mod machine;

pub use approval::{create as create_approval, list_unresolved, resolve as resolve_approval};
pub use db::{Db, DbError, DbResult};

/// crate 名常量，供主程序装配自检（冒烟测试用）。
pub const CRATE_NAME: &str = "core-state";

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn smoke_placeholder() {
        assert_eq!(CRATE_NAME, "core-state");
    }

    /// 建一个内存库 + 一个项目/轮次，返回 (Db, round_id)，供本模块各测试复用。
    fn fixture() -> (Db, i64) {
        let db = Db::open_in_memory().expect("建内存库");
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, workflow_version) VALUES (?1, ?2, ?3)",
                ("demo", "/tmp/demo", "v1.20"),
            )?;
            c.execute(
                "INSERT INTO rounds (project_id, seq, title) VALUES (1, 1, '第一轮')",
                [],
            )?;
            Ok(())
        })
        .expect("造数");
        (db, 1)
    }

    // ---- Step 4 验收：迁移 ----

    #[test]
    fn migrate_is_idempotent() {
        let db = Db::open_in_memory().expect("建库");
        // 重复迁移（模拟客户端升级后再次启动）无副作用
        db.read(|c| {
            db::migrate::migrate(c)?;
            db::migrate::migrate(c)
        })
        .expect("重复迁移");
        let versions = db.read(db::migrate::applied_versions).expect("读版本");
        assert_eq!(
            versions,
            vec![
                "L1".to_string(),
                "L2".to_string(),
                "L3".to_string(),
                "L4".to_string()
            ]
        );
    }

    #[test]
    fn l1_creates_six_tables() {
        let db = Db::open_in_memory().expect("建库");
        let count: i64 = db
            .read(|c| {
                Ok(c.query_row(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name IN
                     ('projects','workflow_states','rounds','tasks','checkpoints','artifacts')",
                    [],
                    |r| r.get(0),
                )?)
            })
            .expect("查表");
        assert_eq!(count, 6);
    }

    // ---- Step 4 验收：写队列并发压测（plan：高频写入无 lock 错误）----

    #[test]
    fn concurrent_writes_serialize_without_lock_errors() {
        let (db, round_id) = fixture();
        let threads: Vec<_> = (0..8)
            .map(|t| {
                let db = db.clone();
                std::thread::spawn(move || {
                    for i in 0..100 {
                        db.write(|c| {
                            c.execute(
                                "INSERT INTO tasks (round_id, chunk_no, title) VALUES (?1, ?2, ?3)",
                                (round_id, t * 100 + i, format!("任务{t}-{i}")),
                            )?;
                            Ok(())
                        })
                        .expect("并发写不报错");
                    }
                })
            })
            .collect();
        for h in threads {
            h.join().expect("线程不 panic");
        }
        let total: i64 = db
            .read(|c| Ok(c.query_row("SELECT COUNT(*) FROM tasks", [], |r| r.get(0))?))
            .expect("统计");
        assert_eq!(total, 800);
    }

    // ---- Step 4 验收：约束与参数化 ----

    #[test]
    fn unique_chunk_no_per_round() {
        let (db, round_id) = fixture();
        db.write(|c| {
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title) VALUES (?1, 1, 'a')",
                [round_id],
            )?;
            Ok(())
        })
        .expect("首插");
        let dup = db.write(|c| {
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title) VALUES (?1, 1, 'b')",
                [round_id],
            )?;
            Ok(())
        });
        assert!(dup.is_err(), "同轮次 chunk_no 重复必须被拒");
    }

    #[test]
    fn foreign_keys_enforced() {
        let (db, _) = fixture();
        let r = db.write(|c| {
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title) VALUES (999, 1, '孤儿')",
                [],
            )?;
            Ok(())
        });
        assert!(r.is_err(), "引用不存在的轮次必须触发外键拒绝");
    }

    // ---- Step 4 验收：文件库 WAL 模式 ----

    #[test]
    fn file_db_uses_wal() {
        let path = std::env::temp_dir().join(format!(
            "devpilot-test-{}-{}.db",
            std::process::id(),
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .map(|d| d.as_nanos())
                .unwrap_or(0)
        ));
        let mode: String = {
            let db = Db::open(&path).expect("开文件库");
            db.read(|c| Ok(c.pragma_query_value(None, "journal_mode", |r| r.get(0))?))
                .expect("读 journal_mode")
        };
        let _ = std::fs::remove_file(&path);
        let _ = std::fs::remove_file(path.with_extension("db-wal"));
        let _ = std::fs::remove_file(path.with_extension("db-shm"));
        assert_eq!(mode.to_lowercase(), "wal");
    }
}
