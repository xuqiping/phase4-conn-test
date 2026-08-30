//! Step 6 状态机引擎验收测试（AC-032 越阶段拒绝 / AC-052 状态机层恢复 / 坏 YAML 降级）。
//! 用例编号对齐 `docs/测试方案/P01客户端骨架与状态机引擎测试方案.md` EC 系列。

use core_state::machine::loader::{self, WorkflowSource};
use core_state::machine::{history, Machine, PersistentMachine, TransitionError};
use core_state::{machine::loader::BUILTIN_DEFAULT_YAML, Db};

fn default_machine(scale: &str) -> Machine {
    let def = loader::parse(BUILTIN_DEFAULT_YAML).expect("内置默认必须可解析");
    Machine::new(def, scale).expect("L2 全流程必须合法")
}

// ---- AC-032：状态机拒绝越阶段操作 ----

#[test]
fn ac032_legal_transition_passes() {
    let mut m = default_machine("L2");
    assert_eq!(m.phase(), "idea");
    m.transition("spec").expect("idea→spec 无门禁");
    assert_eq!(m.phase(), "spec");
}

#[test]
fn ac032_cross_phase_jump_rejected() {
    let m = default_machine("L2");
    // idea 直接跳 build = 越阶段
    let err = m.can_transition("build").unwrap_err();
    assert!(matches!(err, TransitionError::NoEdge { .. }), "{err}");
    // 不存在的阶段
    let err = m.can_transition("mars").unwrap_err();
    assert!(matches!(err, TransitionError::UnknownPhase(_)), "{err}");
}

#[test]
fn ac032_gate_blocks_until_passed() {
    let mut m = default_machine("L2");
    m.transition("spec").unwrap();
    // 需求确认门未过 → 拒绝，且错误里带大白话检查项
    let err = m.can_transition("plan").unwrap_err();
    match &err {
        TransitionError::GateBlocked {
            label, checklist, ..
        } => {
            assert_eq!(label, "需求确认");
            assert!(checklist.iter().any(|c| c.contains("需求卡")));
        }
        other => panic!("应为 GateBlocked，实际 {other}"),
    }
    m.pass_gate("requirement_confirm").unwrap();
    m.transition("plan").expect("过门后可转移");
    assert_eq!(m.phase(), "plan");
}

#[test]
fn ac032_back_transition_allowed_without_gate() {
    let mut m = default_machine("L2");
    m.transition("spec").unwrap();
    m.pass_gate("requirement_confirm").unwrap();
    m.transition("plan").unwrap();
    // 计划退回需求：回退边无门禁
    m.transition("spec").expect("plan→spec 回退合法");
    assert_eq!(m.phase(), "spec");
    // 但 idea→plan 仍非法（无此边）
    let m2 = default_machine("L2");
    assert!(m2.can_transition("plan").is_err());
}

#[test]
fn unknown_gate_rejected() {
    let mut m = default_machine("L2");
    assert!(matches!(
        m.pass_gate("no_such_gate"),
        Err(TransitionError::UnknownGate(_))
    ));
}

// ---- 坏 YAML 降级（plan 坑点表） ----

#[test]
fn bad_yaml_falls_back_to_builtin() {
    let dir = std::env::temp_dir().join(format!("devpilot-yaml-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let bad = dir.join("bad.yaml");
    std::fs::write(&bad, "phases: [{{{{ 这不是合法 YAML").unwrap();
    let loaded = loader::load(Some(&bad));
    assert_eq!(loaded.source, WorkflowSource::Builtin);
    assert!(loaded.warning.is_some(), "必须带降级告警");
    std::fs::remove_dir_all(&dir).ok();
}

#[test]
fn schema_violation_rejected_with_all_problems() {
    // 未定义阶段引用 + 未定义门禁，一次报全
    let yaml = r#"
workflow_version: "9.9"
phases:
  - { key: a, label: A }
  - { key: b, label: B }
transitions:
  - { from: a, to: ghost, gate: nowhere }
"#;
    let err = loader::parse(yaml).unwrap_err();
    assert!(err.contains("ghost"), "{err}");
    assert!(err.contains("nowhere"), "{err}");
}

#[test]
fn unknown_yaml_field_rejected() {
    // deny_unknown_fields：YAML 白名单之外的字​​段必须报错（安全清单）
    let yaml =
        BUILTIN_DEFAULT_YAML.replacen("workflow_version:", "evil_hook: x\nworkflow_version:", 1);
    assert!(loader::parse(&yaml).is_err());
}

// ---- 规模变体（联动点 2） ----

#[test]
fn scale_l0_skips_spec_plan_deploy() {
    let mut m = default_machine("L0");
    let keys: Vec<&str> = m
        .definition()
        .phases
        .iter()
        .map(|p| p.key.as_str())
        .collect();
    assert_eq!(keys, ["idea", "build", "accept"]);
    // 桥接：idea 直达 build（继承 idea→spec 的无门禁）
    m.transition("build").expect("L0 想法直建");
    // 安全门仍在（build→accept）
    assert!(m.can_transition("accept").is_err());
    m.pass_gate("security").unwrap();
    m.transition("accept").unwrap();
    // deploy 已移除
    assert!(matches!(
        m.can_transition("deploy"),
        Err(TransitionError::UnknownPhase(_))
    ));
}

#[test]
fn scale_l1_skips_deploy_only() {
    let m = default_machine("L1");
    let keys: Vec<&str> = m
        .definition()
        .phases
        .iter()
        .map(|p| p.key.as_str())
        .collect();
    assert_eq!(keys, ["idea", "spec", "plan", "build", "accept"]);
}

#[test]
fn scale_bridge_immune_to_backedge_order() {
    // 交叉审查 P02 前修-1：回退边写在正向边之前时，桥接不得把出发阶段变孤岛。
    // 构造：spec 有两条出边（回退 spec→idea 写在前、正向 spec→plan 写在后），
    // L0 删掉 spec/plan 后，idea 必须仍能直达 build。
    let yaml = r#"
workflow_version: 1
phases:
  - { key: idea, label: 想法 }
  - { key: spec, label: 需求 }
  - { key: plan, label: 计划 }
  - { key: build, label: 建造 }
transitions:
  - { from: idea, to: spec }
  - { from: spec, to: idea }    # 回退边抢先
  - { from: spec, to: plan }
  - { from: plan, to: build }
scale_variants:
  L0: { skip: [spec, plan] }
"#;
    let def = loader::parse(yaml).expect("合法工作流");
    let mut m = Machine::new(def, "L0").expect("回退边抢先时桥接仍须成功");
    let keys: Vec<&str> = m
        .definition()
        .phases
        .iter()
        .map(|p| p.key.as_str())
        .collect();
    assert_eq!(keys, ["idea", "build"]);
    m.transition("build").expect("idea 直达 build，未成孤岛");
}

// ---- AC-052：持久化与断点续开（状态机层） ----

fn fixture_db() -> (Db, i64) {
    let db = Db::open_in_memory().unwrap();
    db.write(|c| {
        c.execute(
            "INSERT INTO projects (name, path, workflow_version) VALUES ('demo', '/tmp/demo', 'v1.20')",
            [],
        )?;
        Ok(())
    })
    .unwrap();
    (db, 1)
}

#[test]
fn ac052_state_persists_and_recovers() {
    let (db, pid) = fixture_db();
    let def = loader::parse(BUILTIN_DEFAULT_YAML).unwrap();
    {
        let mut pm = PersistentMachine::load_or_init(db.clone(), pid, def.clone(), "L2").unwrap();
        pm.transition("spec", "user").unwrap();
        pm.pass_gate("requirement_confirm").unwrap();
        pm.transition("plan", "user").unwrap();
    }
    // 模拟杀进程重开：重新 load_or_init 恢复阶段与门禁
    let pm = PersistentMachine::load_or_init(db.clone(), pid, def, "L2").unwrap();
    assert_eq!(pm.machine().phase(), "plan");
    assert!(pm.machine().gates_passed().contains("requirement_confirm"));

    // 历史可回放（运维清单）
    let h = history::list(&db, pid).unwrap();
    let path: Vec<(&str, &str)> = h
        .iter()
        .map(|e| (e.from_phase.as_str(), e.to_phase.as_str()))
        .collect();
    assert_eq!(path, [("idea", "spec"), ("spec", "plan")]);
    assert_eq!(h[1].gate.as_deref(), Some("requirement_confirm"));
}

#[test]
fn ac052_restore_rejects_phase_not_in_def() {
    // 工作流升级后旧阶段不存在 → 明确报错而非静默错乱
    let def = loader::parse(BUILTIN_DEFAULT_YAML).unwrap();
    let mut m = Machine::new(def, "L2").unwrap();
    assert!(m.restore("ghost_phase", vec![]).is_err());
}

#[test]
fn concurrent_transitions_do_not_tear_state_and_history() {
    // 交叉审查 P02 期间修-3：transition 的当前态+历史必须在同一 write 闭包内落库。
    // 4 线程并发对同一项目推 idea→spec：恰好一个成功；最终态与历史行数必须一致
    // （旧实现读-改-写分离，可能出现 2 行历史但状态只前进一步、或互相覆盖）。
    let db = Db::open_in_memory().unwrap();
    db.write(|c| {
        c.execute(
            "INSERT INTO projects (name, path, workflow_version) VALUES ('demo', '/tmp/demo', 'v1.20')",
            [],
        )?;
        Ok(())
    })
    .unwrap();
    let def = loader::parse(BUILTIN_DEFAULT_YAML).unwrap();

    let handles: Vec<_> = (0..4)
        .map(|_| {
            let db = db.clone();
            let def = def.clone();
            std::thread::spawn(move || {
                let mut pm = PersistentMachine::load_or_init(db, 1, def, "L2").expect("加载成功");
                pm.transition("spec", "user").is_ok()
            })
        })
        .collect();
    let ok_count = handles
        .into_iter()
        .map(|h| h.join().unwrap())
        .filter(|ok| *ok)
        .count();

    assert_eq!(ok_count, 1, "idea→spec 只能成功一次");
    let (phase, history_rows): (String, i64) = db
        .read(|c| {
            let phase: String = c.query_row(
                "SELECT phase FROM workflow_states WHERE project_id = 1",
                [],
                |r| r.get(0),
            )?;
            let history_rows: i64 = c.query_row(
                "SELECT COUNT(*) FROM transition_history WHERE project_id = 1",
                [],
                |r| r.get(0),
            )?;
            Ok((phase, history_rows))
        })
        .unwrap();
    assert_eq!(phase, "spec");
    assert_eq!(history_rows, 1, "状态与历史必须一一对应，不得撕裂");
}
