//! S4 集成测试：市场安装链路走真进程（假 server）——安装即 running（AC-012）。
use core_mcp::market::{install, params_from_entry, EnvSpec, InstallParams, MarketEntry};
use core_mcp::Manager;
use core_state::Db;
use std::collections::HashMap;
use std::time::Duration;

fn fake_entry() -> MarketEntry {
    MarketEntry {
        name: "fake-market".into(),
        description: "市场测试 server".into(),
        runtime: "other".into(),
        command: env!("CARGO_BIN_EXE_mcp-fake-server").into(),
        args: vec!["ok".into()],
        env: vec![],
    }
}

#[tokio::test]
async fn install_from_market_starts_and_marks_running() {
    let db = Db::open_in_memory().unwrap();
    let mut mgr = Manager::new(db.clone());
    mgr.init_timeout = Duration::from_secs(2);
    mgr.auto_restart = false;

    let entry = fake_entry();
    let params = params_from_entry(&entry, &HashMap::new()).unwrap();
    // 探测注入：假 server 本身可执行
    let probe = format!("{} ok", env!("CARGO_BIN_EXE_mcp-fake-server"));
    let out = install(&db, Some(&mgr), params, Some(&probe))
        .await
        .expect("安装成功");
    assert_eq!(out.outcome, "installed_and_running");
    assert!(out.message.contains("立即可用"), "{}", out.message);
    let row = db
        .read(|c| core_state::mcp_store::by_id(c, out.id))
        .unwrap()
        .unwrap();
    assert_eq!(row.status, "running");
    mgr.stop(out.id).await.unwrap();
}

#[tokio::test]
async fn install_start_failure_keeps_record_for_retry() {
    let db = Db::open_in_memory().unwrap();
    let mut mgr = Manager::new(db.clone());
    mgr.init_timeout = Duration::from_secs(1);
    mgr.auto_restart = false;

    // silent 模式：握手必超时 → 安装成功但启动失败，记录保留
    let mut entry = fake_entry();
    entry.args = vec!["silent".into()];
    let params = InstallParams {
        name: entry.name.clone(),
        description: entry.description.clone(),
        command: entry.command.clone(),
        args: entry.args.clone(),
        env: HashMap::new(),
    };
    let probe = format!("{} ok", env!("CARGO_BIN_EXE_mcp-fake-server"));
    let out = install(&db, Some(&mgr), params, Some(&probe))
        .await
        .unwrap();
    assert_eq!(out.outcome, "installed_not_started");
    assert!(out.message.contains("重试"), "{}", out.message);
    let row = db
        .read(|c| core_state::mcp_store::by_id(c, out.id))
        .unwrap()
        .unwrap();
    assert_eq!(row.status, "error", "启动失败标 error 供管理页展示");
}

#[tokio::test]
async fn required_env_blocks_install() {
    let db = Db::open_in_memory().unwrap();
    let mut entry = fake_entry();
    entry.env = vec![EnvSpec {
        key: "SECRET".into(),
        description: "必填令牌".into(),
        required: true,
    }];
    let err = params_from_entry(&entry, &HashMap::new()).unwrap_err();
    assert!(err.contains("SECRET"));
    // 没落库
    assert!(db.read(core_state::mcp_store::list).unwrap().is_empty());
}
