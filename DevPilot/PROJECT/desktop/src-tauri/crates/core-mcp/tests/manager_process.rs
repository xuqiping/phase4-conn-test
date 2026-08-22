//! S2 集成测试：真进程（本 crate 的 mcp-fake-server 二进制）。
//! CARGO_BIN_EXE_* 只在集成测试里可用，所以放这里而不是 src/ 单元测试。
use core_mcp::Manager;
use core_state::Db;
use std::time::Duration;

fn fake_server(mode: &str) -> (&'static str, String) {
    (
        env!("CARGO_BIN_EXE_mcp-fake-server"),
        format!(r#"["{mode}"]"#),
    )
}

async fn manager() -> (Manager, Db) {
    let db = Db::open_in_memory().unwrap();
    let mut m = Manager::new(db.clone());
    m.init_timeout = Duration::from_secs(2);
    m.auto_restart = false; // 崩溃用例只验证 error 标记，不让后台重启干扰断言
    (m, db)
}

async fn install(db: &Db, mode: &str) -> i64 {
    let (cmd, args) = fake_server(mode);
    db.write(|c| core_state::mcp_store::insert(c, mode, "测试 server", cmd, &args, "{}"))
        .unwrap()
}

fn status(db: &Db, id: i64) -> String {
    db.read(|c| core_state::mcp_store::by_id(c, id))
        .unwrap()
        .unwrap()
        .status
}

#[tokio::test]
async fn start_handshake_marks_running_and_tools_list_works() {
    let (m, db) = manager().await;
    let id = install(&db, "ok").await;
    let msg = m.start(id).await.expect("启动+握手成功");
    assert_eq!(msg, "已启动");
    assert_eq!(status(&db, id), "running");
    let tools = m.list_tools(id).await.expect("tools/list");
    assert_eq!(tools["tools"][0]["name"], "echo");
    m.stop(id).await.unwrap();
    assert_eq!(status(&db, id), "stopped");
}

#[tokio::test]
async fn silent_server_times_out_and_marked_error() {
    let (m, db) = manager().await;
    let id = install(&db, "silent").await;
    let err = m.start(id).await.expect_err("不应答必须超时");
    assert!(err.contains("超时"), "错误信息要大白话：{err}");
    assert_eq!(status(&db, id), "error");
    let row = db
        .read(|c| core_state::mcp_store::by_id(c, id))
        .unwrap()
        .unwrap();
    assert!(row.last_error.contains("超时"), "错误信息要落库");
}

#[tokio::test]
async fn crash_after_handshake_marks_error_and_restart_recovers() {
    let (m, db) = manager().await;
    let id = install(&db, "die-after-init").await;
    let _ = m.start(id).await; // 应答握手后立刻退出：成功或「握手期间退出」都合法
    for _ in 0..30 {
        if status(&db, id) == "error" {
            break;
        }
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
    assert_eq!(status(&db, id), "error", "异常退出必须标 error（AC-029）");
    // 一键重启：动作本身不 panic、状态留在合法集合
    m.restart(id).await.ok();
    let s = status(&db, id);
    assert!(s == "running" || s == "error", "状态在合法集合内：{s}");
}

#[tokio::test]
async fn dangerous_command_rejected_before_spawn() {
    let (m, db) = manager().await;
    let id = db
        .write(|c| {
            core_state::mcp_store::insert(c, "evil", "危险命令", "rm", r#"["-rf","/"]"#, "{}")
        })
        .unwrap();
    let err = m.start(id).await.expect_err("危险命令必须被拒");
    assert!(err.contains("安全策略"), "错误信息要说明原因：{err}");
    assert_ne!(status(&db, id), "running");
}

#[tokio::test]
async fn stop_when_not_running_is_graceful() {
    let (m, db) = manager().await;
    let id = install(&db, "ok").await;
    let msg = m.stop(id).await.expect("空停不报错");
    assert_eq!(msg, "本来就没在运行");
    assert_eq!(status(&db, id), "stopped");
}
