//! P07 Phase 4 性能冒烟（plan 运维清单两条硬指标）：
//! ① 5 个 MCP server 并发启动 < 5s；② 20+ 技能列表查询 < 200ms（瞬时）。
//! 跑法：`cargo test -p core-mcp --test perf_smoke -- --nocapture`
use core_state::Db;
use std::time::{Duration, Instant};

fn fake_server() -> (&'static str, String) {
    (
        env!("CARGO_BIN_EXE_mcp-fake-server"),
        r#"["ok"]"#.to_string(),
    )
}

#[tokio::test]
async fn five_servers_concurrent_start_under_5s() {
    let db = Db::open_in_memory().unwrap();
    let mut m = core_mcp::Manager::new(db.clone());
    m.init_timeout = Duration::from_secs(2);
    let mut ids = Vec::new();
    for i in 0..5 {
        let (cmd, args) = fake_server();
        ids.push(
            db.write(|c| {
                core_state::mcp_store::insert(c, &format!("perf-{i}"), "性能", cmd, &args, "{}")
            })
            .unwrap(),
        );
    }
    let t0 = Instant::now();
    let starts: Vec<_> = ids.iter().map(|&id| m.start(id)).collect();
    for s in starts {
        s.await.unwrap();
    }
    let elapsed = t0.elapsed();
    let running = ids
        .iter()
        .filter(|&&id| {
            db.read(|c| core_state::mcp_store::by_id(c, id))
                .unwrap()
                .map(|r| r.status == "running")
                .unwrap_or(false)
        })
        .count();
    for &id in &ids {
        let _ = m.stop(id).await;
    }
    println!("5 server 并发启动耗时: {elapsed:?}");
    assert_eq!(running, 5, "全部应 running");
    assert!(elapsed < Duration::from_secs(5), "超 5s：{elapsed:?}");
}

#[test]
fn twenty_skills_list_under_200ms() {
    let db = Db::open_in_memory().unwrap();
    let dir = std::env::temp_dir().join(format!("devpilot-perf-skills-{}", std::process::id()));
    for i in 0..20 {
        let d = dir.join(format!("perf-skill-{i}"));
        std::fs::create_dir_all(&d).unwrap();
        std::fs::write(
            d.join("SKILL.md"),
            format!("---\nname: perf-skill-{i}\ndescription: 性能\n---\n正文"),
        )
        .unwrap();
    }
    core_skills::registry::scan_and_register(&db, &dir).unwrap();
    let t0 = Instant::now();
    let rows = db
        .read(|c| core_state::skills_local::list(c, true))
        .unwrap();
    let elapsed = t0.elapsed();
    println!("20 技能列表耗时: {elapsed:?}");
    assert_eq!(rows.len(), 20);
    assert!(
        elapsed < Duration::from_millis(200),
        "超 200ms：{elapsed:?}"
    );
    std::fs::remove_dir_all(&dir).ok();
}
