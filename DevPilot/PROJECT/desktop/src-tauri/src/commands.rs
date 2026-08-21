//! IPC commands：前端操作状态机的唯一入口（单一真相源在 Rust，plan 坑点表）。
//! 错误上抛只带大白话 message + 分类 code，不含路径/堆栈（plan 安全清单）。

use core_orchestrator::acceptance::{parse_project_test_plans, AcceptanceMethod};
use core_orchestrator::diff_summarizer::{self, DiffSummary};
use core_orchestrator::task_scheduler::{HttpLlmClient, RunResult, Scheduler};
use core_state::acceptance_checklist::{self, AcceptanceItem, NewAcceptanceItem};
use core_state::agent_config::AgentConfigFields;
use core_state::checkpoint;
use core_state::machine::loader;
use core_state::machine::{PersistentMachine, TransitionError};
use core_state::task_event::{self, TaskEvent};
use core_state::Db;
use rusqlite::OptionalExtension;
use serde::Serialize;
use tauri::{AppHandle, Manager, State};

use crate::events;

/// 内核共享状态：本地库句柄（Db 内部已串行化，可直接共享）。
pub struct AppState {
    pub db: Db,
}

#[derive(Debug, Serialize)]
pub struct CmdError {
    pub code: String,
    pub message: String,
}

type CmdResult<T> = Result<T, CmdError>;

fn err(code: &str, message: impl Into<String>) -> CmdError {
    CmdError {
        code: code.into(),
        message: message.into(),
    }
}

impl From<TransitionError> for CmdError {
    fn from(e: TransitionError) -> Self {
        err("TRANSITION", e.to_string())
    }
}

impl From<core_state::DbError> for CmdError {
    fn from(e: core_state::DbError) -> Self {
        err("DB", e.to_string())
    }
}

impl From<diff_summarizer::SummarizerError> for CmdError {
    fn from(e: diff_summarizer::SummarizerError) -> Self {
        match e {
            diff_summarizer::SummarizerError::Git(msg) => err("GIT", msg),
            diff_summarizer::SummarizerError::Llm(msg) => err("LLM", msg),
            diff_summarizer::SummarizerError::Parse(msg) => err("PARSE", msg),
        }
    }
}

// ---------- DTO ----------

#[derive(Debug, Serialize, Clone)]
pub struct ProjectDto {
    pub id: i64,
    pub name: String,
    pub path: String,
    pub scale: String,
    pub current_phase: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct PhaseDto {
    pub key: String,
    pub label: String,
    /// done / active / todo
    pub status: String,
}

#[derive(Debug, Serialize, Clone)]
pub struct GateDto {
    pub key: String,
    pub label: String,
    pub checklist: Vec<String>,
    pub passed: bool,
}

#[derive(Debug, Serialize, Clone)]
pub struct StateDto {
    pub project_id: i64,
    pub phase: String,
    pub workflow_version: String,
    pub phases: Vec<PhaseDto>,
    /// 当前阶段出边上的门禁（含是否已过）
    pub pending_gates: Vec<GateDto>,
    /// 当前阶段可转移的目标 key 列表（是否被门禁拦由内核裁决）
    pub allowed_next: Vec<String>,
    pub warning: Option<String>,
}

// ---------- 内部工具 ----------

/// 路径安全（plan 安全清单）：项目名不得含路径分隔/穿越符；
/// 规范化后必须落在所选父目录内。
fn resolve_project_dir(parent: &str, name: &str) -> CmdResult<std::path::PathBuf> {
    if name.trim().is_empty() || name.len() > 50 {
        return Err(err("BAD_INPUT", "项目名需为 1~50 个字符"));
    }
    if name.contains(['/', '\\']) || name.contains("..") {
        return Err(err("BAD_INPUT", "项目名不能包含路径符号"));
    }
    let parent_path = std::path::Path::new(parent);
    std::fs::create_dir_all(parent_path).map_err(|_| err("IO", "父目录无法创建，请检查权限"))?;
    let canonical_parent = parent_path
        .canonicalize()
        .map_err(|_| err("IO", "父目录无效"))?;
    let dir = canonical_parent.join(name.trim());
    if !dir.starts_with(&canonical_parent) {
        return Err(err("BAD_INPUT", "项目路径越界"));
    }
    if dir.exists() {
        return Err(err("BAD_INPUT", "同名目录已存在，换个名字或换父目录"));
    }
    Ok(dir)
}

/// 加载某项目的持久化状态机（无状态命令：DB 即真相）。
fn load_machine(
    db: &Db,
    project_id: i64,
) -> CmdResult<(PersistentMachine, core_state::machine::WorkflowDef)> {
    let (scale,): (String,) = db
        .read(|c| {
            c.query_row(
                "SELECT scale FROM projects WHERE id = ?1",
                [project_id],
                |r| Ok((r.get(0)?,)),
            )
            .map(Some)
            .or(Ok(None))
        })?
        .ok_or_else(|| err("NOT_FOUND", "项目不存在或已被移除"))?;
    let loaded = loader::load(None); // 外部 YAML 配置开关留到设置页（plan 运维清单）
    let machine =
        PersistentMachine::load_or_init(db.clone(), project_id, loaded.def.clone(), &scale)
            .map_err(|e| err("STATE", e.to_string()))?;
    Ok((machine, loaded.def))
}

/// 若指定门禁未过且当前阶段匹配，自动尝试过门禁（P04 S6 联动点）。
fn auto_pass_gate(db: &Db, project_id: i64, gate: &str) -> CmdResult<()> {
    let (mut machine, _) = load_machine(db, project_id)?;
    if machine.machine().gates_passed().contains(gate) {
        return Ok(());
    }
    machine
        .pass_gate(gate)
        .map_err(|e| err("STATE", e.to_string()))?;
    Ok(())
}

/// 将当前机器状态快照推送给前端（门禁自动解锁后保持 UI 同步）。
fn emit_current_state(db: &Db, app: &AppHandle, project_id: i64) -> CmdResult<()> {
    let (machine, _) = load_machine(db, project_id)?;
    let dto = to_state_dto(&machine, None);
    events::emit_state(app, &dto);
    Ok(())
}

fn to_state_dto(machine: &PersistentMachine, warning: Option<String>) -> StateDto {
    let m = machine.machine();
    let def = m.definition();
    let current_order = def.phases.iter().position(|p| p.key == m.phase());
    let phases = def
        .phases
        .iter()
        .enumerate()
        .map(|(i, p)| PhaseDto {
            key: p.key.clone(),
            label: p.label.clone(),
            status: match current_order {
                Some(cur) if i < cur => "done",
                Some(cur) if i == cur => "active",
                _ => "todo",
            }
            .to_string(),
        })
        .collect();
    // 当前阶段的出边与门禁
    let mut pending_gates = Vec::new();
    let mut allowed_next = Vec::new();
    for t in &def.transitions {
        if t.from != m.phase() {
            continue;
        }
        allowed_next.push(t.to.clone());
        if let Some(g) = &t.gate {
            if !pending_gates.iter().any(|x: &GateDto| x.key == *g) {
                let gd = def.gates.get(g);
                pending_gates.push(GateDto {
                    key: g.clone(),
                    label: gd.map(|d| d.label.clone()).unwrap_or_else(|| g.clone()),
                    checklist: gd.map(|d| d.checklist.clone()).unwrap_or_default(),
                    passed: m.gates_passed().contains(g),
                });
            }
        }
    }
    StateDto {
        project_id: machine.project_id(),
        phase: m.phase().to_string(),
        workflow_version: def.workflow_version.clone(),
        phases,
        pending_gates,
        allowed_next,
        warning,
    }
}

// ---------- commands ----------

#[tauri::command]
pub fn list_projects(state: State<'_, AppState>) -> CmdResult<Vec<ProjectDto>> {
    state
        .db
        .read(|c| {
            let mut stmt =
                c.prepare("SELECT id, name, path, scale, current_phase FROM projects ORDER BY id")?;
            let rows = stmt.query_map([], |r| {
                Ok(ProjectDto {
                    id: r.get(0)?,
                    name: r.get(1)?,
                    path: r.get(2)?,
                    scale: r.get(3)?,
                    current_phase: r.get(4)?,
                })
            })?;
            Ok(rows.collect::<Result<Vec<_>, _>>()?)
        })
        .map_err(|e| err("DB", e.to_string()))
}

#[tauri::command]
pub fn create_project(
    state: State<'_, AppState>,
    app: AppHandle,
    name: String,
    parent_dir: Option<String>,
    scale: String,
) -> CmdResult<ProjectDto> {
    if !["L0", "L1", "L2", "L3"].contains(&scale.as_str()) {
        return Err(err("BAD_INPUT", "规模需为 L0~L3"));
    }
    let parent = match parent_dir.filter(|p| !p.trim().is_empty()) {
        Some(p) => p,
        None => app
            .path()
            .home_dir()
            .map_err(|e| err("IO", e.to_string()))?
            .join("DevPilotProjects")
            .to_string_lossy()
            .to_string(),
    };
    let dir = resolve_project_dir(&parent, &name)?;

    // 项目目录初始化：workflow_output/ 三件套（对齐工作流模板）
    for sub in [
        "workflow_output/docs",
        "workflow_output/开发进度",
        "workflow_output/项目规范约束",
    ] {
        std::fs::create_dir_all(dir.join(sub)).map_err(|e| err("IO", e.to_string()))?;
    }

    let loaded = loader::load(None);
    let path_str = dir.to_string_lossy().to_string();
    let id: i64 = state
        .db
        .write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, scale, workflow_version) VALUES (?1, ?2, ?3, ?4)",
                (name.trim(), &path_str, &scale, &loaded.def.workflow_version),
            )?;
            Ok(c.last_insert_rowid())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    let (machine, _) = load_machine(&state.db, id)?;
    let dto = to_state_dto(&machine, loaded.warning);
    events::emit_state(&app, &dto);

    Ok(ProjectDto {
        id,
        name: name.trim().to_string(),
        path: path_str,
        scale,
        current_phase: dto.phase.clone(),
    })
}

#[tauri::command]
pub fn get_state(state: State<'_, AppState>, project_id: i64) -> CmdResult<StateDto> {
    let (machine, _) = load_machine(&state.db, project_id)?;
    Ok(to_state_dto(&machine, None))
}

#[tauri::command]
pub fn transition(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
    to: String,
) -> CmdResult<StateDto> {
    let (mut machine, _) = load_machine(&state.db, project_id)?;
    machine
        .transition(&to, "user")
        .map_err(|e| err("TRANSITION", e.to_string()))?;
    // 同步 projects.current_phase（列表页展示用）
    let phase = machine.machine().phase().to_string();
    state
        .db
        .write(|c| {
            c.execute(
                "UPDATE projects SET current_phase = ?1, updated_at = datetime('now') WHERE id = ?2",
                (&phase, project_id),
            )?;
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;
    let dto = to_state_dto(&machine, None);
    events::emit_state(&app, &dto);
    Ok(dto)
}

#[tauri::command]
pub fn pass_gate(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
    gate: String,
) -> CmdResult<StateDto> {
    let (mut machine, _) = load_machine(&state.db, project_id)?;
    machine
        .pass_gate(&gate)
        .map_err(|e| err("TRANSITION", e.to_string()))?;
    let dto = to_state_dto(&machine, None);
    events::emit_state(&app, &dto);
    Ok(dto)
}

// ---------- P06 S1：验收门禁与发布入口（FR-040 / AC-044） ----------

/// 从「建造」阶段进入「验收」阶段。
/// L2/L3 项目必须已经通过 security 门禁（由 run_security_scan 解锁）；
/// L0/L1 项目若门禁未过则自动放行（安全扫描为建议不拦截）。
#[tauri::command]
pub async fn enter_acceptance(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
) -> CmdResult<StateDto> {
    let (mut machine, _) = load_machine(&state.db, project_id)?;
    if machine.machine().phase() != "build" {
        return Err(err("STATE", "只有「建造」阶段才能进入验收"));
    }

    // 门禁已解锁：直接转移。
    if machine.machine().can_transition("accept").is_ok() {
        return transition_to(&state.db, &app, &mut machine, project_id, "accept").await;
    }

    // 门禁未解锁：按规模处理。
    let scale: String = state
        .db
        .read(|c| {
            c.query_row(
                "SELECT scale FROM projects WHERE id = ?1",
                [project_id],
                |r| r.get::<_, String>(0),
            )
            .map_err(Into::into)
        })
        .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;

    if matches!(scale.as_str(), "L2" | "L3") {
        // Phase4 审查修复（AC-044 UI 死锁）：扫描入口原本只在验收视图里，
        // 而门禁未过进不了验收视图 → 用户无路可走。改为进入验收时自动扫描：
        // 通过则解锁并进入；未通过则拦截并列出问题数。
        let scan = perform_security_scan(&state, project_id).await?;
        if scan.status == "pass" {
            if let Some(w) = scan.warning {
                return Err(err("STATE", w));
            }
            machine
                .pass_gate("security")
                .map_err(|e| err("STATE", e.to_string()))?;
            return transition_to(&state.db, &app, &mut machine, project_id, "accept").await;
        }
        let critical = scan
            .findings
            .iter()
            .filter(|f| matches!(f.severity.as_str(), "critical" | "high"))
            .count();
        return Err(err(
            "GATE_BLOCKED",
            format!(
                "安全扫描未通过（共 {} 条风险，其中高危 {} 条），请先修复再进入验收。详情可在验收视图安全面板查看",
                scan.findings.len(),
                critical
            ),
        ));
    }

    // L0/L1：security 门禁自动放行。
    machine
        .pass_gate("security")
        .map_err(|e| err("STATE", e.to_string()))?;
    transition_to(&state.db, &app, &mut machine, project_id, "accept").await
}

/// 从「验收」阶段请求发布到「部署」阶段。
/// 只有全部验收项为 pass/na 时才允许通过 release 门禁。
#[tauri::command]
pub async fn request_release(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
) -> CmdResult<StateDto> {
    let (mut machine, _) = load_machine(&state.db, project_id)?;
    if machine.machine().phase() != "accept" {
        return Err(err("STATE", "只有「验收」阶段才能发布"));
    }

    // 检查是否存在发布目标阶段（L0/L1 无 deploy，直接提示无需发布）。
    if machine.machine().gate_for("deploy").is_none()
        && machine.machine().can_transition("deploy").is_err()
    {
        return Ok(to_state_dto(
            &machine,
            Some("当前项目规模无需发布阶段".into()),
        ));
    }

    // 全部验收项通过才可发布。
    let blocked: bool = state
        .db
        .read(|c| {
            // Phase4 审查 C6：清单为空同样拦截（空清单 ≠ 全部通过），与前端口径对齐。
            let unresolved: i64 = c.query_row(
                "SELECT COUNT(*) FROM acceptance_items WHERE project_id = ?1 AND status NOT IN ('pass', 'na')",
                [project_id],
                |r| r.get(0),
            )?;
            let total: i64 = c.query_row(
                "SELECT COUNT(*) FROM acceptance_items WHERE project_id = ?1",
                [project_id],
                |r| r.get(0),
            )?;
            Ok(unresolved > 0 || total == 0)
        })
        .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;

    if blocked {
        return Err(err(
            "RELEASE_BLOCKED",
            "还有未通过/未验收的验收项，或验收清单为空，无法发布",
        ));
    }

    machine
        .pass_gate("release")
        .map_err(|e| err("STATE", e.to_string()))?;
    transition_to(&state.db, &app, &mut machine, project_id, "deploy").await
}

/// 内部辅助：执行状态转移并同步 projects.current_phase、推送状态快照。
async fn transition_to(
    db: &Db,
    app: &AppHandle,
    machine: &mut PersistentMachine,
    project_id: i64,
    to: &str,
) -> CmdResult<StateDto> {
    machine
        .transition(to, "devpilot")
        .map_err(|e| err("TRANSITION", e.to_string()))?;
    let phase = machine.machine().phase().to_string();
    db.write(|c| {
        c.execute(
            "UPDATE projects SET current_phase = ?1, updated_at = datetime('now') WHERE id = ?2",
            (&phase, project_id),
        )?;
        Ok(())
    })
    .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;
    let dto = to_state_dto(machine, None);
    events::emit_state(app, &dto);
    Ok(dto)
}

// ---------- P06 S4/S5：安全扫描引擎与门禁（FR-040 / AC-044） ----------

#[derive(Debug, Serialize)]
pub struct SecurityFindingDto {
    pub severity: String,
    pub category: String,
    pub message: String,
    pub file: String,
    pub line: usize,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub snippet: Option<String>,
    pub suggestion: String,
}

#[derive(Debug, Serialize)]
pub struct SecurityScanDto {
    pub status: String,
    pub findings: Vec<SecurityFindingDto>,
    pub gate_passed: bool,
    pub warning: Option<String>,
}

impl From<core_orchestrator::security_scanner::Finding> for SecurityFindingDto {
    fn from(f: core_orchestrator::security_scanner::Finding) -> Self {
        Self {
            severity: f.severity,
            category: f.category,
            message: f.message,
            file: f.file,
            line: f.line,
            snippet: f.snippet,
            suggestion: f.suggestion,
        }
    }
}

fn scan_status_to_string(status: &core_orchestrator::security_scanner::ScanStatus) -> String {
    use core_orchestrator::security_scanner::ScanStatus;
    match status {
        ScanStatus::Pass => "pass".into(),
        ScanStatus::Fail => "fail".into(),
        ScanStatus::Partial => "partial".into(),
    }
}

/// 对指定项目执行本地安全扫描。
/// - 结果写入 security_scans 与 acceptance_runs（kind='security'）历史。
/// - L2/L3 扫描通过时自动解锁 security 门禁；扫描失败仍允许进入验收（前端再点一次进入验收会提示）。
#[tauri::command]
pub async fn run_security_scan(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
) -> CmdResult<SecurityScanDto> {
    let dto = perform_security_scan(&state, project_id).await?;
    emit_current_state(&state.db, &app, project_id)?;
    Ok(dto)
}

/// 扫描主体（run_security_scan 与 enter_acceptance 自动扫描共用）。
async fn perform_security_scan(
    state: &State<'_, AppState>,
    project_id: i64,
) -> CmdResult<SecurityScanDto> {
    let path = project_path(&state.db, project_id)?;
    let scale: String = state
        .db
        .read(|c| {
            c.query_row(
                "SELECT scale FROM projects WHERE id = ?1",
                [project_id],
                |r| r.get::<_, String>(0),
            )
            .map_err(Into::into)
        })
        .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;

    let run_id = state
        .db
        .write(|c| core_state::acceptance_run::start(c, project_id, "security"))
        .map_err(|e| err("DB", e.to_string()))?;

    let scanner = core_orchestrator::security_scanner::SecurityScanner::new(&path, &scale);
    let report = tokio::task::block_in_place(move || scanner.scan());

    let findings: Vec<SecurityFindingDto> = report.findings.into_iter().map(Into::into).collect();
    let findings_json =
        serde_json::to_string(&findings).map_err(|e| err("SERIALIZE", e.to_string()))?;
    let status_str = scan_status_to_string(&report.status);

    let summary = serde_json::json!({
        "status": status_str,
        "finding_count": findings.len(),
    })
    .to_string();

    state
        .db
        .write(|c| {
            core_state::security_scan::insert(c, project_id, &status_str, &findings_json)?;
            core_state::acceptance_run::finish(c, run_id, &status_str, &summary)?;
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    // L2/L3 通过时自动解锁 security 门禁，保持状态机同步。
    let mut gate_passed = false;
    let mut warning = None;
    if matches!(scale.as_str(), "L2" | "L3") && status_str == "pass" {
        if let Err(e) = auto_pass_gate(&state.db, project_id, "security") {
            warning = Some(format!("扫描通过，但解锁门禁失败：{}", e.message));
        } else {
            gate_passed = true;
        }
    }

    Ok(SecurityScanDto {
        status: status_str.clone(),
        findings,
        gate_passed,
        warning,
    })
}

// ---------- P06 S7：元素圈选 → 修复任务（FR-033 / AC-037） ----------

#[derive(Debug, serde::Deserialize)]
pub struct CreateFixTaskReq {
    pub project_id: i64,
    /// 关联的验收项（可空：从预览直接圈选时无关联项）。
    #[serde(default)]
    pub acceptance_item_id: Option<i64>,
    pub selector: String,
    pub description: String,
}

/// 库内插入 fix task 并回写 acceptance_items.fix_task_id（S10 复用）。
fn insert_fix_task(
    c: &rusqlite::Connection,
    project_id: i64,
    acceptance_item_id: Option<i64>,
    selector: &str,
    description: &str,
) -> core_state::DbResult<i64> {
    let instructions = format!(
        "修复验收发现的问题。\n元素定位：{selector}\n问题描述：{description}\n完成后请自测该交互。"
    );
    // 取当前 open 轮次；没有则自动开一轮（与 execute_build 同语义）。
    let round_id: Option<i64> = c
        .query_row(
            "SELECT id FROM rounds WHERE project_id = ?1 AND status = 'open' ORDER BY seq DESC LIMIT 1",
            [project_id],
            |r| r.get(0),
        )
        .optional()?;
    let round_id = match round_id {
        Some(id) => id,
        None => {
            c.execute(
                "INSERT INTO rounds (project_id, seq, title, status) VALUES (?1, (SELECT COALESCE(MAX(seq),0)+1 FROM rounds WHERE project_id = ?1), '验收修复轮', 'open')",
                [project_id],
            )?;
            c.last_insert_rowid()
        }
    };
    c.execute(
        "INSERT INTO tasks (round_id, chunk_no, title, status, source, instructions)
         VALUES (?1, (SELECT COALESCE(MAX(chunk_no),0)+1 FROM tasks WHERE round_id = ?1), ?2, 'pending', 'fix', ?3)",
        (round_id, format!("修复：{description}"), &instructions),
    )?;
    let task_id = c.last_insert_rowid();
    if let Some(item_id) = acceptance_item_id {
        acceptance_checklist::set_fix_task(c, item_id, Some(task_id))?;
    }
    Ok(task_id)
}

/// 校验 fix task 输入长度（plan 安全清单）。
fn validate_fix_input<'a>(
    selector: &'a str,
    description: &'a str,
) -> CmdResult<(&'a str, &'a str)> {
    let selector = selector.trim();
    let description = description.trim();
    // Phase4 修复：按字符数限长（与前端 maxLength 同口径），字节长度会把中文提前拦掉。
    if selector.is_empty() || selector.chars().count() > 200 {
        return Err(err("BAD_INPUT", "元素定位信息需为 1~200 个字符"));
    }
    if description.is_empty() || description.chars().count() > 1000 {
        return Err(err("BAD_INPUT", "问题描述需为 1~1000 个字符"));
    }
    Ok((selector, description))
}

/// 圈选元素后创建修复任务：当前 open round 末尾插入 source='fix' 的 pending task，
/// 并回写 acceptance_items.fix_task_id（同一 item 只关联最新一个 fix_task）。
#[tauri::command]
pub fn create_fix_task(
    state: State<'_, AppState>,
    app: AppHandle,
    req: CreateFixTaskReq,
) -> CmdResult<i64> {
    let (selector, description) = validate_fix_input(&req.selector, &req.description)?;
    let task_id = state
        .db
        .write(|c| {
            insert_fix_task(
                c,
                req.project_id,
                req.acceptance_item_id,
                selector,
                description,
            )
        })
        .map_err(|e| err("DB", e.to_string()))?;
    emit_current_state(&state.db, &app, req.project_id)?;
    Ok(task_id)
}

// ---------- P06 S8：Playwright 冒烟执行（FR-052 / AC-058） ----------

#[derive(Debug, Serialize)]
pub struct SmokeRunDto {
    pub passed: usize,
    pub failed: usize,
    pub skipped: usize,
    pub warning: Option<String>,
}

/// 对 auto 验收项跑一次 Playwright 冒烟：结果回写 acceptance_items 并推送状态。
#[tauri::command]
pub async fn run_smoke_check(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
    base_url: Option<String>,
) -> CmdResult<SmokeRunDto> {
    let path = project_path(&state.db, project_id)?;
    let url = base_url.unwrap_or_else(|| "http://localhost:5173".into());
    let run_id = state
        .db
        .write(|c| core_state::acceptance_run::start(c, project_id, "smoke"))
        .map_err(|e| err("DB", e.to_string()))?;

    let outcome = core_orchestrator::acceptance::smoke::run_smoke(
        &state.db,
        project_id,
        std::path::Path::new(&path),
        &url,
    )
    .await
    .map_err(|e| err("DB", e.to_string()))?;

    let summary = serde_json::json!({
        "passed": outcome.passed,
        "failed": outcome.failed,
        "skipped": outcome.skipped,
    })
    .to_string();
    let status = if outcome.failed == 0 { "pass" } else { "fail" };
    state
        .db
        .write(|c| core_state::acceptance_run::finish(c, run_id, status, &summary))
        .map_err(|e| err("DB", e.to_string()))?;

    emit_current_state(&state.db, &app, project_id)?;
    Ok(SmokeRunDto {
        passed: outcome.passed,
        failed: outcome.failed,
        skipped: outcome.skipped,
        warning: outcome.warning,
    })
}

// ---------- P06 S2/S3：验收清单解析与持久化（FR-033 / AC-036） ----------

#[derive(Debug, Serialize)]
pub struct AcceptanceItemDto {
    pub id: i64,
    pub project_id: i64,
    pub source_file: String,
    pub tc_id: String,
    pub title: String,
    pub steps: String,
    pub expected: String,
    pub method: String,
    pub status: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub evidence_path: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub fix_task_id: Option<i64>,
    pub sort_order: i32,
}

impl From<AcceptanceItem> for AcceptanceItemDto {
    fn from(item: AcceptanceItem) -> Self {
        Self {
            id: item.id,
            project_id: item.project_id,
            source_file: item.source_file,
            tc_id: item.tc_id,
            title: item.title,
            steps: item.steps,
            expected: item.expected,
            method: item.method,
            status: item.status,
            evidence_path: item.evidence_path,
            fix_task_id: item.fix_task_id,
            sort_order: item.sort_order,
        }
    }
}

/// 读取某项目的全部验收项。
#[tauri::command]
pub fn get_acceptance_checklist(
    state: State<'_, AppState>,
    project_id: i64,
) -> CmdResult<Vec<AcceptanceItemDto>> {
    let rows = state
        .db
        .read(|c| acceptance_checklist::list(c, project_id))?;
    Ok(rows.into_iter().map(Into::into).collect())
}

/// 重新解析测试方案并生成验收清单。
#[tauri::command]
pub fn regenerate_acceptance_checklist(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
) -> CmdResult<Vec<AcceptanceItemDto>> {
    let path = project_path(&state.db, project_id)?;
    let drafts = parse_project_test_plans(std::path::Path::new(&path))
        .map_err(|e| err("PARSE", e.to_string()))?;

    let items: Vec<NewAcceptanceItem> = drafts
        .into_iter()
        .enumerate()
        .map(|(i, d)| NewAcceptanceItem {
            source_file: d.source_file,
            tc_id: d.tc_id,
            title: d.title,
            steps: d.steps,
            expected: d.expected,
            method: match d.method {
                AcceptanceMethod::Auto => "auto",
                AcceptanceMethod::Manual => "manual",
            }
            .to_string(),
            sort_order: i as i32,
        })
        .collect();

    let rows = state.db.write(|c| {
        acceptance_checklist::regenerate(c, project_id, &items)?;
        acceptance_checklist::list(c, project_id)
    })?;

    emit_current_state(&state.db, &app, project_id)?;
    Ok(rows.into_iter().map(Into::into).collect())
}

#[derive(Debug, serde::Deserialize)]
pub struct UpdateAcceptanceItemReq {
    pub id: i64,
    pub status: String,
    #[serde(default)]
    pub evidence_path: Option<String>,
    /// S10：status=fail 时可携带圈选信息，同步创建修复任务。
    #[serde(default)]
    pub selector: Option<String>,
    #[serde(default)]
    pub description: Option<String>,
}

/// 更新验收项状态与证据路径；fail 时可顺手生成修复任务（S10 联动）。
#[tauri::command]
pub fn update_acceptance_item(
    state: State<'_, AppState>,
    app: AppHandle,
    req: UpdateAcceptanceItemReq,
) -> CmdResult<AcceptanceItemDto> {
    if !matches!(req.status.as_str(), "pending" | "pass" | "fail" | "na") {
        return Err(err("BAD_INPUT", "状态需为 pending/pass/fail/na 之一"));
    }
    let fix_input = match (req.status.as_str(), &req.selector, &req.description) {
        ("fail", Some(sel), Some(desc)) => {
            let (s, d) = validate_fix_input(sel, desc)?;
            Some((s.to_string(), d.to_string()))
        }
        _ => None,
    };

    let updated = state
        .db
        .write(|c| {
            acceptance_checklist::update_status(
                c,
                req.id,
                &req.status,
                req.evidence_path.as_deref(),
            )?;
            if let Some((sel, desc)) = &fix_input {
                insert_fix_task(
                    c,
                    /* project 由 item 推导 */
                    {
                        let item = acceptance_checklist::get(c, req.id)?.ok_or_else(|| {
                            core_state::DbError::Io(std::io::Error::other("验收项不存在"))
                        })?;
                        item.project_id
                    },
                    Some(req.id),
                    sel,
                    desc,
                )?;
            }
            acceptance_checklist::get(c, req.id)?
                .ok_or_else(|| core_state::DbError::Io(std::io::Error::other("验收项不存在")))
        })
        .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;
    emit_current_state(&state.db, &app, updated.project_id)?;
    Ok(updated.into())
}

// ---------- P02 Step8：云端计费客户端半边（vault + 用量镜像） ----------

/// refresh token 存 OS 凭据管理器（Windows 凭据管理器；绝不落明文文件）。
#[tauri::command]
pub fn vault_save(refresh_token: String) -> CmdResult<()> {
    use core_meter::TokenVault as _;
    core_meter::OsKeyringVault::new()
        .save(&refresh_token)
        .map_err(|e| err("VAULT", e.to_string()))
}

#[tauri::command]
pub fn vault_load() -> CmdResult<Option<String>> {
    use core_meter::TokenVault as _;
    core_meter::OsKeyringVault::new()
        .load()
        .map_err(|e| err("VAULT", e.to_string()))
}

#[tauri::command]
pub fn vault_clear() -> CmdResult<()> {
    use core_meter::TokenVault as _;
    core_meter::OsKeyringVault::new()
        .clear()
        .map_err(|e| err("VAULT", e.to_string()))
}

/// 云端账本行同步进本地镜像（幂等，重推零新增）。
#[tauri::command]
pub fn meter_sync(
    state: State<'_, AppState>,
    user_id: i64,
    rows: Vec<core_meter::CloudLedgerRow>,
) -> CmdResult<usize> {
    core_meter::MeterMirror::new(state.db.clone())
        .sync_from_cloud(user_id, &rows)
        .map_err(|e| err("DB", e.to_string()))
}

/// 对账：本地镜像推导余额 vs 云端 /balance。不平=漂移，上层记日志告警。
#[tauri::command]
pub fn meter_reconcile(
    state: State<'_, AppState>,
    user_id: i64,
    cloud_cents: i64,
) -> CmdResult<core_meter::ReconcileReport> {
    core_meter::MeterMirror::new(state.db.clone())
        .reconcile(user_id, cloud_cents)
        .map_err(|e| err("DB", e.to_string()))
}

// ---------- 审批（P03 Step3 FR-009） ----------

#[derive(Debug, serde::Serialize)]
pub struct ApprovalDto {
    pub id: i64,
    pub project_id: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub task_id: Option<i64>,
    pub kind: String,
    pub title: String,
    pub detail: String,
    pub risk_level: String,
}

impl From<core_state::approval::PendingApproval> for ApprovalDto {
    fn from(a: core_state::approval::PendingApproval) -> Self {
        Self {
            id: a.id,
            project_id: a.project_id,
            task_id: a.task_id,
            kind: a.kind,
            title: a.title,
            detail: a.detail,
            risk_level: a.risk_level,
        }
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct CreateApprovalReq {
    pub project_id: i64,
    #[serde(default)]
    pub task_id: Option<i64>,
    pub kind: String,
    pub title: String,
    pub detail: String,
    pub risk_level: String,
}

/// 创建一条待审批记录。
#[tauri::command]
pub fn create_approval(state: State<'_, AppState>, req: CreateApprovalReq) -> CmdResult<i64> {
    state
        .db
        .write(|c| {
            core_state::create_approval(
                c,
                req.project_id,
                req.task_id,
                &req.kind,
                &req.title,
                &req.detail,
                &req.risk_level,
            )
        })
        .map_err(Into::into)
}

/// 列出某项目未解决的审批。
#[tauri::command]
pub fn list_unresolved_approvals(
    state: State<'_, AppState>,
    project_id: i64,
) -> CmdResult<Vec<ApprovalDto>> {
    let rows = state
        .db
        .read(|c| core_state::list_unresolved(c, project_id))?;
    Ok(rows.into_iter().map(Into::into).collect())
}

/// 提交审批决定：true=允许，false=拒绝。
#[tauri::command]
pub fn submit_approval(state: State<'_, AppState>, id: i64, allow: bool) -> CmdResult<bool> {
    state
        .db
        .write(|c| core_state::resolve_approval(c, id, allow))
        .map_err(Into::into)
}

// ---------- 安装向导（P03 Step5 FR-005/AC-006） ----------

#[derive(Debug, serde::Serialize)]
pub struct InstallPlanDto {
    pub missing: Vec<String>,
    pub steps: Vec<InstallStepDto>,
}

#[derive(Debug, serde::Serialize)]
pub struct InstallStepDto {
    pub name: String,
    pub command: String,
    pub estimated_seconds: u32,
    pub risk_note: String,
}

impl From<core_exec::InstallStep> for InstallStepDto {
    fn from(s: core_exec::InstallStep) -> Self {
        Self {
            name: s.name,
            command: s.command.join(" "),
            estimated_seconds: s.estimated_seconds,
            risk_note: s.risk_note,
        }
    }
}

impl From<core_exec::InstallPlan> for InstallPlanDto {
    fn from(p: core_exec::InstallPlan) -> Self {
        Self {
            missing: p.missing,
            steps: p.steps.into_iter().map(Into::into).collect(),
        }
    }
}

/// 生成项目安装计划（不执行）。
#[tauri::command]
pub fn install_plan(state: State<'_, AppState>, project_id: i64) -> CmdResult<InstallPlanDto> {
    let path: String = state.db.read(|c| {
        c.query_row(
            "SELECT path FROM projects WHERE id = ?1",
            [project_id],
            |r| r.get::<_, String>(0),
        )
        .map_err(Into::into)
    })?;
    let profile = state.db.write(|c| {
        core_exec::probe_and_cache(c, std::path::Path::new(&path))
            .map_err(|e| core_state::DbError::Io(std::io::Error::other(e.to_string())))
    })?;
    Ok(core_exec::install_plan(&profile).into())
}

#[derive(Debug, serde::Serialize)]
pub struct InstallResultDto {
    pub step: String,
    pub success: bool,
    pub stdout: String,
    pub stderr: String,
}

impl From<core_exec::InstallResult> for InstallResultDto {
    fn from(r: core_exec::InstallResult) -> Self {
        Self {
            step: r.step,
            success: r.success,
            stdout: r.stdout,
            stderr: r.stderr,
        }
    }
}

/// 执行安装计划（逐个运行，失败即停）。
#[tauri::command]
pub async fn install_runtime(
    state: State<'_, AppState>,
    project_id: i64,
) -> CmdResult<Vec<InstallResultDto>> {
    let path: String = state.db.read(|c| {
        c.query_row(
            "SELECT path FROM projects WHERE id = ?1",
            [project_id],
            |r| r.get::<_, String>(0),
        )
        .map_err(Into::into)
    })?;
    let profile = state.db.write(|c| {
        core_exec::probe_and_cache(c, std::path::Path::new(&path))
            .map_err(|e| core_state::DbError::Io(std::io::Error::other(e.to_string())))
    })?;
    let plan = core_exec::install_plan(&profile);
    let results = core_exec::run_install_plan(&plan, std::path::Path::new(&path), |_line| {
        // 后续可接前端事件流（FR-038 日志透明层）
    })
    .await;
    Ok(results.into_iter().map(Into::into).collect())
}

// ---------- 全流程任务执行（P03 Step6 FR-003/AC-004） ----------

fn project_path(db: &Db, project_id: i64) -> CmdResult<String> {
    db.read(|c| {
        c.query_row(
            "SELECT path FROM projects WHERE id = ?1",
            [project_id],
            |r| r.get::<_, String>(0),
        )
        .map_err(Into::into)
    })
    .map_err(|e| err("DB", e.to_string()))
}

/// 读取项目内文件（受沙箱约束）。
#[tauri::command]
pub fn read_project_file(
    state: State<'_, AppState>,
    project_id: i64,
    rel_path: String,
) -> CmdResult<String> {
    let path = project_path(&state.db, project_id)?;
    core_exec::read_project_file(std::path::Path::new(&path), &rel_path)
        .map_err(|e| err("SANDBOX", e.to_string()))
}

/// 写入项目内文件（受沙箱约束）。
#[tauri::command]
pub fn write_project_file(
    state: State<'_, AppState>,
    project_id: i64,
    rel_path: String,
    content: String,
) -> CmdResult<()> {
    let path = project_path(&state.db, project_id)?;
    core_exec::write_project_file(std::path::Path::new(&path), &rel_path, &content)
        .map_err(|e| err("SANDBOX", e.to_string()))
}

/// 运行一次任务闭环：写文件→安装→测试/lint→修复→commit 存档点。
#[tauri::command]
pub async fn run_task(
    state: State<'_, AppState>,
    app: AppHandle,
    req: core_exec::TaskRequest,
) -> CmdResult<core_exec::TaskResult> {
    let path = project_path(&state.db, req.project_id)?;
    let result = tokio::task::block_in_place(|| {
        state.db.write(|c| {
            let names = core_state::secrets::list(c, req.project_id)?;
            let mut secrets = Vec::new();
            for m in names {
                if let Some(v) = core_state::secrets::load(c, req.project_id, &m.name)? {
                    secrets.push(core_exec::MaskedSecret::new(m.name, v));
                }
            }
            let mut events: Vec<TaskEvent> = Vec::new();
            let r = tokio::runtime::Handle::current().block_on(core_exec::run_task(
                Some(c),
                &req,
                std::path::Path::new(&path),
                &core_exec::NoOpFixStrategy,
                &secrets,
                |ev| {
                    events.push(ev.clone());
                    events::emit_task_event(&app, ev);
                },
            ));
            for ev in &events {
                let _ = task_event::insert(c, ev.task_id, ev.event_type, &ev.message);
            }
            Ok::<_, core_state::DbError>(r)
        })
    });
    result.map_err(|e| err("DB", e.to_string()))
}

// ---------- Secrets 管理（P03 Step7 FR-012/AC-014） ----------

#[derive(Debug, serde::Serialize)]
pub struct SecretMetaDto {
    pub id: i64,
    pub project_id: i64,
    pub name: String,
}

impl From<core_state::secrets::SecretMeta> for SecretMetaDto {
    fn from(m: core_state::secrets::SecretMeta) -> Self {
        Self {
            id: m.id,
            project_id: m.project_id,
            name: m.name,
        }
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct SaveSecretReq {
    pub project_id: i64,
    pub name: String,
    pub value: String,
}

/// 保存/更新 secret。值不落 DB，走 keyring 或 AES-256-GCM 加密。
#[tauri::command]
pub fn save_secret(state: State<'_, AppState>, req: SaveSecretReq) -> CmdResult<i64> {
    state
        .db
        .write(|c| core_state::secrets::save(c, req.project_id, &req.name, &req.value))
        .map_err(|e| err("DB", e.to_string()))
}

/// 列出某项目的 secret 名称（不含值）。
#[tauri::command]
pub fn list_secrets(state: State<'_, AppState>, project_id: i64) -> CmdResult<Vec<SecretMetaDto>> {
    state
        .db
        .read(|c| core_state::secrets::list(c, project_id))
        .map(|rows| rows.into_iter().map(Into::into).collect())
        .map_err(|e| err("DB", e.to_string()))
}

/// 读取某个 secret 值（仅用于编辑回填，调用方需自行脱敏展示）。
#[tauri::command]
pub fn load_secret(
    state: State<'_, AppState>,
    project_id: i64,
    name: String,
) -> CmdResult<Option<String>> {
    state
        .db
        .read(|c| core_state::secrets::load(c, project_id, &name))
        .map_err(|e| err("DB", e.to_string()))
}

/// 删除 secret。
#[tauri::command]
pub fn delete_secret(state: State<'_, AppState>, project_id: i64, name: String) -> CmdResult<bool> {
    state
        .db
        .write(|c| core_state::secrets::delete(c, project_id, &name))
        .map_err(|e| err("DB", e.to_string()))
}

// ---------- 状态机集成：建造阶段自动执行（P03 Step8 FR-003 联动） ----------

/// 在「建造」阶段触发一次本地任务闭环；成功后自动推进到「验收」，失败留在建造阶段。
#[tauri::command]
pub async fn execute_build(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
    access_token: String,
    cloud_base: Option<String>,
) -> CmdResult<StateDto> {
    let (mut machine, _) = load_machine(&state.db, project_id)?;
    if machine.machine().phase() != "build" {
        return Err(err("STATE", "只有「建造」阶段才能执行构建"));
    }

    // 取当前 open 轮次；没有则自动开一轮。
    let round_id: i64 = state
        .db
        .write(|c| {
            let id: Option<i64> = c
                .query_row(
                    "SELECT id FROM rounds WHERE project_id = ?1 AND status = 'open' ORDER BY seq DESC LIMIT 1",
                    [project_id],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(id) = id {
                return Ok(id);
            }
            c.execute(
                "INSERT INTO rounds (project_id, seq, title, status) VALUES (?1, (SELECT COALESCE(MAX(seq),0)+1 FROM rounds WHERE project_id = ?1), '自动构建轮', 'open')",
                [project_id],
            )?;
            Ok(c.last_insert_rowid())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    let path = project_path(&state.db, project_id)?;
    let base = cloud_base.unwrap_or_else(|| "http://127.0.0.1:3000/api/v1".into());
    let llm = HttpLlmClient::new(base, access_token);
    let scheduler = Scheduler {
        db: &state.db,
        project_id,
        round_id,
        project_path: std::path::Path::new(&path),
        llm: &llm,
    };

    let app_for_events = app.clone();
    let mut events: Vec<TaskEvent> = Vec::new();
    let result = scheduler
        .run_round(|ev| {
            events.push(ev.clone());
            events::emit_task_event(&app_for_events, ev);
        })
        .await
        .map_err(|e| err("SCHEDULER", e.to_string()))?;

    // 持久化事件流。
    state
        .db
        .write(|c| {
            for ev in &events {
                let _ = task_event::insert(c, ev.task_id, ev.event_type, &ev.message);
            }
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    // 根据编排结果推进状态机。
    let mut warning: Option<String> = None;
    match result {
        RunResult::Done {
            total_cost_cents: _,
        } => {
            if let Err(e) = machine.transition("accept", "devpilot") {
                warning = Some(e.to_string());
            } else {
                let phase = machine.machine().phase().to_string();
                state
                    .db
                    .write(|c| {
                        c.execute(
                            "UPDATE projects SET current_phase = ?1, updated_at = datetime('now') WHERE id = ?2",
                            (&phase, project_id),
                        )?;
                        Ok(())
                    })
                    .map_err(|e| err("DB", e.to_string()))?;
            }
            if warning.is_none() {
                // 总消耗已分别记入每个 task 的 cost_cents；usage_mirror 由消费端写入。
            }
        }
        RunResult::Failed { task_id } => {
            warning = Some(format!("任务 {} 执行失败，已停止后续 chunk", task_id));
        }
        RunResult::NeedClarify { task_id, questions } => {
            warning = Some(format!(
                "任务 {} 需要澄清：{}",
                task_id,
                questions.join("；")
            ));
        }
    }

    let dto = to_state_dto(&machine, warning);
    events::emit_state(&app, &dto);
    Ok(dto)
}

// ---------- diff 大白话摘要（P05 S3 FR-013） ----------

#[derive(Debug, Serialize)]
pub struct DiffSummaryDto {
    pub what_changed: String,
    pub why: String,
    pub impact: String,
    pub risk: String,
    pub files: Vec<String>,
    pub truncated: bool,
    pub raw_diff: String,
}

impl DiffSummaryDto {
    fn from_summary(s: DiffSummary, raw_diff: String) -> Self {
        Self {
            what_changed: s.what_changed,
            why: s.why,
            impact: s.impact,
            risk: s.risk,
            files: s.files,
            truncated: s.truncated,
            raw_diff,
        }
    }
}

/// 为某任务的存档点生成 diff 大白话摘要；结果写回 checkpoints.summary_plain。
#[tauri::command]
pub async fn summarize_diff(
    state: State<'_, AppState>,
    project_id: i64,
    task_id: i64,
    access_token: String,
    cloud_base: Option<String>,
) -> CmdResult<DiffSummaryDto> {
    let cp = state
        .db
        .read(|c| checkpoint::get_by_task(c, task_id))?
        .ok_or_else(|| err("NOT_FOUND", "该任务暂无存档点"))?;
    let commit = cp
        .git_commit
        .ok_or_else(|| err("NOT_FOUND", "存档点没有 commit 哈希"))?;
    let path = project_path(&state.db, project_id)?;
    let base = cloud_base.unwrap_or_else(|| "http://127.0.0.1:3000/api/v1".into());
    let llm = HttpLlmClient::new(base, access_token);
    let (summary, raw) =
        diff_summarizer::summarize(std::path::Path::new(&path), &commit, &llm).await?;

    let summary_json =
        serde_json::to_string(&summary).map_err(|e| err("SERIALIZE", e.to_string()))?;
    state
        .db
        .write(|c| {
            checkpoint::update_summary(c, task_id, &summary_json)?;
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    Ok(DiffSummaryDto::from_summary(summary, raw))
}

// ---------- 存档点列表与回滚（P05 S5 FR-037） ----------

#[derive(Debug, Serialize)]
pub struct CheckpointDto {
    pub id: i64,
    pub task_id: i64,
    pub chunk_no: i64,
    pub round_id: i64,
    pub title: String,
    pub status: String,
    pub git_commit: String,
    pub summary_plain: String,
    pub created_at: String,
}

impl From<core_state::checkpoint::Checkpoint> for CheckpointDto {
    fn from(cp: core_state::checkpoint::Checkpoint) -> Self {
        Self {
            id: cp.id,
            task_id: cp.task_id,
            chunk_no: cp.chunk_no.unwrap_or(0),
            round_id: cp.round_id.unwrap_or(0),
            title: cp.title.unwrap_or_default(),
            status: cp.status.unwrap_or_default(),
            git_commit: cp.git_commit.unwrap_or_default(),
            summary_plain: cp.summary_plain,
            created_at: cp.created_at,
        }
    }
}

/// 列出某项目的全部存档点（按 chunk 顺序）。
#[tauri::command]
pub fn list_checkpoints(
    state: State<'_, AppState>,
    project_id: i64,
) -> CmdResult<Vec<CheckpointDto>> {
    let rows = state
        .db
        .read(|c| checkpoint::list_by_project(c, project_id))?;
    Ok(rows.into_iter().map(Into::into).collect())
}

/// 回滚到指定 checkpoint：git reset --hard + 下游 tasks 重置为 pending。
#[tauri::command]
pub fn rollback_to_checkpoint(
    state: State<'_, AppState>,
    app: AppHandle,
    checkpoint_id: i64,
) -> CmdResult<StateDto> {
    let cp = state
        .db
        .read(|c| checkpoint::get(c, checkpoint_id))?
        .ok_or_else(|| err("NOT_FOUND", "存档点不存在或已被移除"))?;
    let chunk_no = cp
        .chunk_no
        .ok_or_else(|| err("STATE", "存档点缺少 chunk 信息"))?;
    let round_id = cp
        .round_id
        .ok_or_else(|| err("STATE", "存档点缺少轮次信息"))?;
    let commit = cp
        .git_commit
        .ok_or_else(|| err("STATE", "存档点没有 commit 哈希"))?;

    let project_id: i64 = state
        .db
        .read(|c| {
            c.query_row(
                "SELECT r.project_id FROM rounds r JOIN tasks t ON t.round_id = r.id WHERE t.id = ?1",
                [cp.task_id],
                |r| r.get(0),
            )
            .map_err(Into::into)
        })
        .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;

    let path = project_path(&state.db, project_id)?;
    let out = std::process::Command::new("git")
        .args(["reset", "--hard", &commit])
        .current_dir(&path)
        .output()
        .map_err(|e| err("GIT", e.to_string()))?;
    if !out.status.success() {
        return Err(err("GIT", String::from_utf8_lossy(&out.stderr).to_string()));
    }

    state
        .db
        .write(|c| {
            checkpoint::rollback_downstream(c, round_id, chunk_no)?;
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    let (machine, _) = load_machine(&state.db, project_id)?;
    let dto = to_state_dto(
        &machine,
        Some(format!(
            "已回滚到 checkpoint {}（commit {}）",
            checkpoint_id,
            &commit[..8.min(commit.len())]
        )),
    );
    events::emit_state(&app, &dto);
    Ok(dto)
}

// ---------- 追加指令续跑（P05 S6 FR-015） ----------

/// 在当前 open 轮次末尾追加一条新 task 并立即跑 scheduler（不推进状态机）。
#[tauri::command]
pub async fn continue_task(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
    instructions: String,
    access_token: String,
    cloud_base: Option<String>,
) -> CmdResult<StateDto> {
    let round_id: i64 = state
        .db
        .read(|c| {
            c.query_row(
                "SELECT id FROM rounds WHERE project_id = ?1 AND status = 'open' ORDER BY seq DESC LIMIT 1",
                [project_id],
                |r| r.get(0),
            )
            .map_err(Into::into)
        })
        .map_err(|e| err("DB", e.to_string()))?;

    let path = project_path(&state.db, project_id)?;
    let base = cloud_base.unwrap_or_else(|| "http://127.0.0.1:3000/api/v1".into());
    let llm = HttpLlmClient::new(base, access_token);
    let scheduler = Scheduler {
        db: &state.db,
        project_id,
        round_id,
        project_path: std::path::Path::new(&path),
        llm: &llm,
    };

    scheduler
        .append_task("追加指令", &instructions)
        .map_err(|e| err("DB", e.to_string()))?;

    let app_for_events = app.clone();
    let mut events: Vec<TaskEvent> = Vec::new();
    let result = scheduler
        .run_round(|ev| {
            events.push(ev.clone());
            events::emit_task_event(&app_for_events, ev);
        })
        .await
        .map_err(|e| err("SCHEDULER", e.to_string()))?;

    state
        .db
        .write(|c| {
            for ev in &events {
                let _ = task_event::insert(c, ev.task_id, ev.event_type, &ev.message);
            }
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    let (machine, _) = load_machine(&state.db, project_id)?;
    let warning = match result {
        RunResult::Done { .. } => None,
        RunResult::Failed { task_id } => Some(format!("追加任务 {} 执行失败，已停止", task_id)),
        RunResult::NeedClarify { task_id, questions } => Some(format!(
            "追加任务 {} 需要澄清：{}",
            task_id,
            questions.join("；")
        )),
    };
    let dto = to_state_dto(&machine, warning);
    events::emit_state(&app, &dto);
    Ok(dto)
}

// ---------- Build 视图 + 驾驶舱真实数据（P05 S7 FR-038/039/043） ----------

#[derive(Debug, Serialize)]
pub struct TaskDto {
    pub id: i64,
    pub round_id: i64,
    pub chunk_no: i64,
    pub title: String,
    pub status: String,
    pub instructions: String,
    pub cost_cents: i64,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub started_at: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub finished_at: Option<String>,
}

#[derive(Debug, Serialize)]
pub struct RoundDto {
    pub id: i64,
    pub seq: i64,
    pub title: String,
    pub status: String,
    pub total_tasks: i64,
    pub done_tasks: i64,
}

fn current_open_round_id(
    c: &rusqlite::Connection,
    project_id: i64,
) -> core_state::DbResult<Option<i64>> {
    c.query_row(
        "SELECT id FROM rounds WHERE project_id = ?1 AND status = 'open' ORDER BY seq DESC LIMIT 1",
        [project_id],
        |r| r.get(0),
    )
    .optional()
    .map_err(Into::into)
}

/// 列出某项目任务；不传 round_id 时默认取当前 open round。
/// S10 联动：source='fix' 的任务已完成 → 关联验收项自动重置 pending（等待重新验收）。
#[tauri::command]
pub fn list_tasks(
    state: State<'_, AppState>,
    project_id: i64,
    round_id: Option<i64>,
) -> CmdResult<Vec<TaskDto>> {
    state
        .db
        .write(|c| {
            c.execute(
                "UPDATE acceptance_items SET status = 'pending', fix_task_id = NULL
                 WHERE status != 'pending' AND fix_task_id IN (
                   SELECT id FROM tasks WHERE source = 'fix' AND status = 'done'
                 ) AND project_id = ?1",
                [project_id],
            )?;
            Ok(())
        })
        .map_err(|e: core_state::DbError| err("DB", e.to_string()))?;

    let rid = match round_id {
        Some(r) => r,
        None => state
            .db
            .read(|c| current_open_round_id(c, project_id))?
            .unwrap_or(-1),
    };
    if rid < 0 {
        return Ok(Vec::new());
    }
    let rows = state.db.read(|c| {
        let mut stmt = c.prepare(
            "SELECT id, round_id, chunk_no, title, status, instructions, cost_cents, started_at, finished_at
             FROM tasks WHERE round_id = ?1 ORDER BY chunk_no",
        )?;
        let rows = stmt.query_map([rid], |r| {
            Ok(TaskDto {
                id: r.get(0)?,
                round_id: r.get(1)?,
                chunk_no: r.get(2)?,
                title: r.get(3)?,
                status: r.get(4)?,
                instructions: r.get(5)?,
                cost_cents: r.get(6).unwrap_or(0),
                started_at: r.get(7)?,
                finished_at: r.get(8)?,
            })
        })?;
        Ok(rows.collect::<Result<Vec<_>, _>>()?)
    })?;
    Ok(rows)
}

/// 列出某项目的全部轮次及任务统计。
#[tauri::command]
pub fn list_rounds(state: State<'_, AppState>, project_id: i64) -> CmdResult<Vec<RoundDto>> {
    let rows = state.db.read(|c| {
        let mut stmt = c.prepare(
            "SELECT r.id, r.seq, r.title, r.status,
                    COUNT(t.id) AS total,
                    SUM(CASE WHEN t.status = 'done' THEN 1 ELSE 0 END) AS done
             FROM rounds r
             LEFT JOIN tasks t ON t.round_id = r.id
             WHERE r.project_id = ?1
             GROUP BY r.id
             ORDER BY r.seq",
        )?;
        let rows = stmt.query_map([project_id], |r| {
            Ok(RoundDto {
                id: r.get(0)?,
                seq: r.get(1)?,
                title: r.get(2)?,
                status: r.get(3)?,
                total_tasks: r.get(4).unwrap_or(0),
                done_tasks: r.get(5).unwrap_or(0),
            })
        })?;
        Ok(rows.collect::<Result<Vec<_>, _>>()?)
    })?;
    Ok(rows)
}

/// 列出某任务的事件流（叙事/原始日志/错误/存档点）。
#[tauri::command]
pub fn list_task_events(state: State<'_, AppState>, task_id: i64) -> CmdResult<Vec<TaskEvent>> {
    let rows = state
        .db
        .read(|c| Ok(core_state::task_event::list_by_task(c, task_id)?))?;
    Ok(rows)
}

// ---------- AGENTS.md 大白话表单（P04 S1 FR-008/AC-009） ----------

/// 加载项目约定表单（无记录时返回默认模板）。
#[tauri::command]
pub fn load_agent_config(
    state: State<'_, AppState>,
    project_id: i64,
) -> CmdResult<AgentConfigFields> {
    state
        .db
        .read(|c| core_state::agent_config::load(c, project_id))
        .map_err(|e| err("DB", e.to_string()))
}

/// 保存项目约定：写库 + 重写项目根 AGENTS.md。
#[tauri::command]
pub fn save_agent_config(
    state: State<'_, AppState>,
    project_id: i64,
    fields: AgentConfigFields,
) -> CmdResult<()> {
    let path = project_path(&state.db, project_id)?;
    state
        .db
        .write(|c| core_state::agent_config::save(c, project_id, &fields))
        .map_err(|e| err("DB", e.to_string()))?;
    let md = core_state::agent_config::render(&fields);
    let agents_path = std::path::Path::new(&path).join("AGENTS.md");
    std::fs::write(&agents_path, md).map_err(|e| err("IO", e.to_string()))?;
    Ok(())
}

// ---------- 需求确认卡片（P04 S4 FR-031/AC-034） ----------

#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct SpecCardDto {
    pub id: i64,
    pub project_id: i64,
    pub title: String,
    pub detail: String,
    pub ac: Vec<String>,
    pub status: String,
}

impl From<core_state::spec_card::SpecCard> for SpecCardDto {
    fn from(c: core_state::spec_card::SpecCard) -> Self {
        Self {
            id: c.id,
            project_id: c.project_id,
            title: c.title,
            detail: c.detail,
            ac: c.ac,
            status: match c.status {
                core_state::spec_card::SpecCardStatus::Confirmed => "confirmed",
                core_state::spec_card::SpecCardStatus::Skipped => "skipped",
                core_state::spec_card::SpecCardStatus::Pending => "pending",
            }
            .into(),
        }
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct SpecCardDraftDto {
    pub title: String,
    pub detail: String,
    pub ac: Vec<String>,
}

#[derive(Debug, serde::Deserialize)]
pub struct SaveSpecCardsReq {
    pub project_id: i64,
    pub cards: Vec<SpecCardDraftDto>,
}

fn parse_spec_status(s: &str) -> Option<core_state::spec_card::SpecCardStatus> {
    match s {
        "confirmed" => Some(core_state::spec_card::SpecCardStatus::Confirmed),
        "skipped" => Some(core_state::spec_card::SpecCardStatus::Skipped),
        "pending" => Some(core_state::spec_card::SpecCardStatus::Pending),
        _ => None,
    }
}

/// 批量保存需求卡：清空旧卡后插入新卡（重新生成报告后使用）。
#[tauri::command]
pub fn save_spec_cards(
    state: State<'_, AppState>,
    req: SaveSpecCardsReq,
) -> CmdResult<Vec<SpecCardDto>> {
    let cards: Vec<core_state::spec_card::SpecCard> = req
        .cards
        .into_iter()
        .map(|d| core_state::spec_card::SpecCard {
            project_id: req.project_id,
            title: d.title,
            detail: d.detail,
            ac: d.ac,
            ..Default::default()
        })
        .collect();
    state
        .db
        .write(|c| {
            core_state::spec_card::clear(c, req.project_id)?;
            core_state::spec_card::insert_batch(c, req.project_id, &cards)?;
            core_state::spec_card::list(c, req.project_id)
        })
        .map_err(|e| err("DB", e.to_string()))
        .map(|rows| rows.into_iter().map(Into::into).collect())
}

/// 列出某项目全部需求卡。
#[tauri::command]
pub fn list_spec_cards(state: State<'_, AppState>, project_id: i64) -> CmdResult<Vec<SpecCardDto>> {
    state
        .db
        .read(|c| core_state::spec_card::list(c, project_id))
        .map_err(|e| err("DB", e.to_string()))
        .map(|rows| rows.into_iter().map(Into::into).collect())
}

#[derive(Debug, serde::Deserialize)]
pub struct UpdateSpecCardReq {
    pub id: i64,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub detail: Option<String>,
    #[serde(default)]
    pub ac: Option<Vec<String>>,
    #[serde(default)]
    pub status: Option<String>,
}

/// 更新需求卡内容或状态。
#[tauri::command]
pub fn update_spec_card(
    state: State<'_, AppState>,
    app: AppHandle,
    req: UpdateSpecCardReq,
) -> CmdResult<SpecCardDto> {
    let status = req.status.as_deref().and_then(parse_spec_status);
    let updated = state
        .db
        .write(|c| {
            core_state::spec_card::update(
                c,
                req.id,
                req.title.as_deref(),
                req.detail.as_deref(),
                req.ac.as_deref(),
                status,
            )?;
            core_state::spec_card::get(c, req.id)
        })
        .map_err(|e| err("DB", e.to_string()))?;
    let updated: SpecCardDto = updated
        .map(Into::into)
        .ok_or_else(|| err("NOT_FOUND", "需求卡不存在"))?;

    // S6：若全部需求卡已确认/跳过，自动解锁「进入计划」门禁。
    let resolved = state
        .db
        .read(|c| core_state::spec_card::all_resolved(c, updated.project_id))
        .map_err(|e| err("DB", e.to_string()))?;
    if resolved {
        auto_pass_gate(&state.db, updated.project_id, "requirement_confirm")?;
        emit_current_state(&state.db, &app, updated.project_id)?;
    }
    Ok(updated)
}

// ---------- 施工计划 chunk 看板（P04 S5 FR-032/AC-035） ----------

#[derive(Debug, serde::Serialize, serde::Deserialize)]
pub struct PlanChunkDto {
    pub id: i64,
    pub project_id: i64,
    pub title: String,
    pub goal: String,
    pub estimated_tokens: Option<i64>,
    pub dependencies: Vec<String>,
    pub status: String,
}

impl From<core_state::plan_chunk::PlanChunk> for PlanChunkDto {
    fn from(c: core_state::plan_chunk::PlanChunk) -> Self {
        Self {
            id: c.id,
            project_id: c.project_id,
            title: c.title,
            goal: c.goal,
            estimated_tokens: c.estimated_tokens,
            dependencies: c.dependencies,
            status: match c.status {
                core_state::plan_chunk::PlanChunkStatus::Approved => "approved",
                core_state::plan_chunk::PlanChunkStatus::Running => "running",
                core_state::plan_chunk::PlanChunkStatus::Done => "done",
                core_state::plan_chunk::PlanChunkStatus::Draft => "draft",
            }
            .into(),
        }
    }
}

#[derive(Debug, serde::Deserialize)]
pub struct PlanChunkDraftDto {
    pub title: String,
    pub goal: String,
    #[serde(default)]
    pub estimated_tokens: Option<i64>,
    #[serde(default)]
    pub dependencies: Vec<String>,
}

#[derive(Debug, serde::Deserialize)]
pub struct SavePlanChunksReq {
    pub project_id: i64,
    pub chunks: Vec<PlanChunkDraftDto>,
}

/// 批量保存施工计划 chunk：清空旧计划后插入新计划（重新生成后使用）。
#[tauri::command]
pub fn save_plan_chunks(
    state: State<'_, AppState>,
    req: SavePlanChunksReq,
) -> CmdResult<Vec<PlanChunkDto>> {
    let chunks: Vec<core_state::plan_chunk::PlanChunk> = req
        .chunks
        .into_iter()
        .map(|d| core_state::plan_chunk::PlanChunk {
            project_id: req.project_id,
            title: d.title,
            goal: d.goal,
            estimated_tokens: d.estimated_tokens,
            dependencies: d.dependencies,
            ..Default::default()
        })
        .collect();
    state
        .db
        .write(|c| {
            core_state::plan_chunk::clear(c, req.project_id)?;
            core_state::plan_chunk::insert_batch(c, req.project_id, &chunks)?;
            core_state::plan_chunk::list(c, req.project_id)
        })
        .map_err(|e| err("DB", e.to_string()))
        .map(|rows| rows.into_iter().map(Into::into).collect())
}

/// 列出某项目全部 chunk。
#[tauri::command]
pub fn list_plan_chunks(
    state: State<'_, AppState>,
    project_id: i64,
) -> CmdResult<Vec<PlanChunkDto>> {
    state
        .db
        .read(|c| core_state::plan_chunk::list(c, project_id))
        .map_err(|e| err("DB", e.to_string()))
        .map(|rows| rows.into_iter().map(Into::into).collect())
}

#[derive(Debug, serde::Deserialize)]
pub struct UpdatePlanChunkReq {
    pub id: i64,
    #[serde(default)]
    pub title: Option<String>,
    #[serde(default)]
    pub goal: Option<String>,
    #[serde(default)]
    pub estimated_tokens: Option<Option<i64>>,
    #[serde(default)]
    pub dependencies: Option<Vec<String>>,
}

/// 更新 chunk 内容（仅 draft 状态可编辑，前端校验）。
#[tauri::command]
pub fn update_plan_chunk(
    state: State<'_, AppState>,
    req: UpdatePlanChunkReq,
) -> CmdResult<PlanChunkDto> {
    let updated = state
        .db
        .write(|c| {
            core_state::plan_chunk::update(
                c,
                req.id,
                req.title.as_deref(),
                req.goal.as_deref(),
                req.estimated_tokens,
                req.dependencies.as_deref(),
            )?;
            core_state::plan_chunk::get(c, req.id)
        })
        .map_err(|e| err("DB", e.to_string()))?;
    updated
        .map(Into::into)
        .ok_or_else(|| err("NOT_FOUND", "施工计划不存在"))
}

/// 审批计划：所有 draft chunk 变为 approved，并按顺序创建 tasks。
#[tauri::command]
pub fn approve_plan(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
) -> CmdResult<Vec<PlanChunkDto>> {
    let rows = state
        .db
        .write(|c| {
            core_state::plan_chunk::approve_all(c, project_id)?;
            let chunks = core_state::plan_chunk::list(c, project_id)?;

            // 取当前 open 轮次，没有则新建。
            let round_id: Option<i64> = c
                .query_row(
                    "SELECT id FROM rounds WHERE project_id = ?1 AND status = 'open' ORDER BY seq DESC LIMIT 1",
                    [project_id],
                    |r| r.get(0),
                )
                .optional()?;
            let round_id = match round_id {
                Some(id) => id,
                None => {
                    c.execute(
                        "INSERT INTO rounds (project_id, seq, title, status) VALUES (?1, (SELECT COALESCE(MAX(seq),0)+1 FROM rounds WHERE project_id = ?1), '计划轮', 'open')",
                        [project_id],
                    )?;
                    c.last_insert_rowid()
                }
            };

            // 删除该轮次下由旧计划生成的 tasks（撤销后重批场景）。
            c.execute(
                "DELETE FROM tasks WHERE round_id = ?1 AND source = 'local' AND status = 'pending'",
                [round_id],
            )?;

            for (i, chunk) in chunks.iter().enumerate() {
                c.execute(
                    "INSERT INTO tasks (round_id, chunk_no, title, status, source) VALUES (?1, ?2, ?3, 'pending', 'local')",
                    (round_id, i as i64 + 1, &chunk.title),
                )?;
            }
            Ok(chunks)
        })
        .map_err(|e| err("DB", e.to_string()))
        .map(|rows| rows.into_iter().map(Into::into).collect())?;

    // S6：审批通过后自动解锁「开工」门禁，并推送状态快照。
    auto_pass_gate(&state.db, project_id, "kickoff")?;
    emit_current_state(&state.db, &app, project_id)?;
    Ok(rows)
}

/// 撤销审批：approved chunk 回到 draft，并删除对应 pending tasks。
#[tauri::command]
pub fn revoke_plan_approval(
    state: State<'_, AppState>,
    app: AppHandle,
    project_id: i64,
) -> CmdResult<Vec<PlanChunkDto>> {
    let rows = state
        .db
        .write(|c| {
            core_state::plan_chunk::revoke_approval(c, project_id)?;
            let round_id: Option<i64> = c
                .query_row(
                    "SELECT id FROM rounds WHERE project_id = ?1 AND status = 'open' ORDER BY seq DESC LIMIT 1",
                    [project_id],
                    |r| r.get(0),
                )
                .optional()?;
            if let Some(round_id) = round_id {
                c.execute(
                    "DELETE FROM tasks WHERE round_id = ?1 AND source = 'local' AND status = 'pending'",
                    [round_id],
                )?;
            }
            core_state::plan_chunk::list(c, project_id)
        })
        .map_err(|e| err("DB", e.to_string()))
        .map(|rows| rows.into_iter().map(Into::into).collect())?;

    // S6：撤销审批后回退「开工」门禁，避免无 tasks 也能 transition 到 build。
    auto_unpass_gate(&state.db, project_id, "kickoff")?;
    emit_current_state(&state.db, &app, project_id)?;
    Ok(rows)
}

/// 若指定门禁已记录在当前阶段，撤销它（撤销计划审批时回退「开工」门禁）。
fn auto_unpass_gate(db: &Db, project_id: i64, gate: &str) -> CmdResult<()> {
    db.write(|c| {
        let row: Option<(String, String)> = c
            .query_row(
                "SELECT phase, gate_status FROM workflow_states WHERE project_id = ?1",
                [project_id],
                |r| Ok((r.get(0)?, r.get(1)?)),
            )
            .optional()?;
        if let Some((phase, gate_status)) = row {
            let mut gates: Vec<String> = serde_json::from_str(&gate_status).unwrap_or_default();
            let before = gates.len();
            gates.retain(|g| g != gate);
            if gates.len() != before {
                let new_json = serde_json::to_string(&gates).unwrap_or_else(|_| "[]".into());
                c.execute(
                    "UPDATE workflow_states SET gate_status = ?1, updated_at = datetime('now') WHERE project_id = ?2 AND phase = ?3",
                    [new_json.as_str(), project_id.to_string().as_str(), phase.as_str()],
                )?;
            }
        }
        Ok(())
    })
    .map_err(|e| err("DB", e.to_string()))
}

#[cfg(test)]
mod tests {
    use super::*;
    use core_state::spec_card::{SpecCard, SpecCardStatus};
    use core_state::Db;

    fn project_fixture(db: &Db) -> i64 {
        db.write(|c| {
            c.execute(
                "INSERT INTO projects (name, path, scale, workflow_version) VALUES ('p', '/tmp/p', 'L1', '1.20')",
                [],
            )?;
            Ok(c.last_insert_rowid())
        })
        .unwrap()
    }

    #[test]
    fn auto_pass_requirement_confirm_when_all_spec_resolved() {
        let db = Db::open_in_memory().unwrap();
        let pid = project_fixture(&db);
        db.write(|c| {
            core_state::spec_card::insert_batch(
                c,
                pid,
                &[
                    SpecCard {
                        project_id: pid,
                        title: "A".into(),
                        ..Default::default()
                    },
                    SpecCard {
                        project_id: pid,
                        title: "B".into(),
                        ..Default::default()
                    },
                ],
            )?;
            Ok(())
        })
        .unwrap();

        let cards = db.read(|c| core_state::spec_card::list(c, pid)).unwrap();
        let id = cards[0].id;
        db.write(|c| {
            core_state::spec_card::update(c, id, None, None, None, Some(SpecCardStatus::Confirmed))
        })
        .unwrap();
        // 只有一张确认，门禁不应解锁
        assert!(!db
            .read(|c| core_state::spec_card::all_resolved(c, pid))
            .unwrap());

        // 确认第二张并自动过门禁
        let id2 = cards[1].id;
        db.write(|c| {
            core_state::spec_card::update(c, id2, None, None, None, Some(SpecCardStatus::Confirmed))
        })
        .unwrap();
        assert!(db
            .read(|c| core_state::spec_card::all_resolved(c, pid))
            .unwrap());
        auto_pass_gate(&db, pid, "requirement_confirm").unwrap();

        let (machine, _) = load_machine(&db, pid).unwrap();
        assert!(machine
            .machine()
            .gates_passed()
            .contains("requirement_confirm"));
    }

    #[test]
    fn auto_pass_kickoff_and_can_unpass() {
        let db = Db::open_in_memory().unwrap();
        let pid = project_fixture(&db);
        auto_pass_gate(&db, pid, "kickoff").unwrap();

        let (machine, _) = load_machine(&db, pid).unwrap();
        assert!(machine.machine().gates_passed().contains("kickoff"));

        auto_unpass_gate(&db, pid, "kickoff").unwrap();
        let (machine, _) = load_machine(&db, pid).unwrap();
        assert!(!machine.machine().gates_passed().contains("kickoff"));
    }

    #[test]
    fn create_fix_task_inserts_pending_fix_task_and_links_item() {
        // AC-037：圈选 → source='fix' pending task + acceptance_items.fix_task_id 回写。
        let db = Db::open_in_memory().unwrap();
        let pid = project_fixture(&db);
        db.write(|c| {
            core_state::acceptance_checklist::regenerate(
                c,
                pid,
                &[core_state::acceptance_checklist::NewAcceptanceItem {
                    source_file: "t.md".into(),
                    tc_id: "TC-01".into(),
                    title: "登录可用".into(),
                    steps: "步骤".into(),
                    expected: "预期".into(),
                    method: "manual".into(),
                    sort_order: 0,
                }],
            )
        })
        .unwrap();
        let item_id = db
            .read(|c| core_state::acceptance_checklist::list(c, pid))
            .unwrap()[0]
            .id;

        let task_id = db
            .write(|c| {
                c.execute(
                    "INSERT INTO rounds (project_id, seq, title, status) VALUES (?1, 1, '轮', 'open')",
                    [pid],
                )?;
                c.execute(
                    "INSERT INTO tasks (round_id, chunk_no, title, status, source, instructions)
                     VALUES (1, 1, '旧任务', 'done', 'local', 'x')",
                    [],
                )?;
                let id = c.last_insert_rowid();
                // 模拟 create_fix_task 的库内逻辑（命令层薄封装，重点验证 SQL 语义）
                c.execute(
                    "INSERT INTO tasks (round_id, chunk_no, title, status, source, instructions)
                     VALUES (1, (SELECT COALESCE(MAX(chunk_no),0)+1 FROM tasks WHERE round_id = 1), ?1, 'pending', 'fix', ?2)",
                    ("修复：按钮没反应".to_string(), "元素定位：坐标(10,20)".to_string()),
                )?;
                let fix_id = c.last_insert_rowid();
                core_state::acceptance_checklist::set_fix_task(c, item_id, Some(fix_id))?;
                Ok(id)
            })
            .unwrap();

        let (source, status, chunk_no): (String, String, i64) = db
            .read(|c| {
                c.query_row(
                    "SELECT source, status, chunk_no FROM tasks WHERE id = (SELECT MAX(id) FROM tasks)",
                    [],
                    |r| Ok((r.get(0)?, r.get(1)?, r.get(2)?)),
                )
                .map_err(Into::into)
            })
            .unwrap();
        assert_eq!((source.as_str(), status.as_str()), ("fix", "pending"));
        assert_eq!(chunk_no, 2, "fix task 追加在轮次末尾");

        let item = db
            .read(|c| core_state::acceptance_checklist::get(c, item_id))
            .unwrap()
            .unwrap();
        assert!(item.fix_task_id.is_some());
        assert!(task_id > 0);
    }

    #[test]
    fn s10_done_fix_task_resets_item_to_pending() {
        // S10 联动：fix 任务 done → 关联验收项从 pass/fail 回到 pending。
        let db = Db::open_in_memory().unwrap();
        let pid = project_fixture(&db);
        db.write(|c| {
            core_state::acceptance_checklist::regenerate(
                c,
                pid,
                &[core_state::acceptance_checklist::NewAcceptanceItem {
                    source_file: "t.md".into(),
                    tc_id: "TC-01".into(),
                    title: "登录可用".into(),
                    steps: "步骤".into(),
                    expected: "预期".into(),
                    method: "manual".into(),
                    sort_order: 0,
                }],
            )?;
            c.execute(
                "INSERT INTO rounds (project_id, seq, title, status) VALUES (?1, 1, '轮', 'open')",
                [pid],
            )?;
            let fix_id = insert_fix_task(c, pid, None, "坐标(1,2)", "按钮没反应")?;
            let item_id = core_state::acceptance_checklist::list(c, pid)?[0].id;
            core_state::acceptance_checklist::set_fix_task(c, item_id, Some(fix_id))?;
            // 先验收通过，再完成修复任务 → 应回 pending
            core_state::acceptance_checklist::update_status(c, item_id, "pass", None)?;
            c.execute("UPDATE tasks SET status = 'done' WHERE id = ?1", [fix_id])?;
            Ok(())
        })
        .unwrap();

        db.write(|c| {
            c.execute(
                "UPDATE acceptance_items SET status = 'pending', fix_task_id = NULL
                 WHERE status != 'pending' AND fix_task_id IN (
                   SELECT id FROM tasks WHERE source = 'fix' AND status = 'done')
                 AND project_id = ?1",
                [pid],
            )?;
            Ok(())
        })
        .unwrap();
        // 注意：Db 的锁不可重入，嵌套 db.read 会死锁——先取 id 再查详情。
        let item_id = db
            .read(|c| core_state::acceptance_checklist::list(c, pid))
            .unwrap()[0]
            .id;
        let item = db
            .read(|c| core_state::acceptance_checklist::get(c, item_id))
            .unwrap()
            .unwrap();
        assert_eq!(item.status, "pending");
        assert!(item.fix_task_id.is_none(), "重置时必须解绑 fix_task_id");

        // Phase4 审查 C1 回归：重置后重新标 pass，联动 SQL 不得再次打回 pending
        // （否则该项目发布被永久卡死）。fix_task_id 已清空，第二次联动是空操作。
        db.write(|c| {
            core_state::acceptance_checklist::update_status(c, item_id, "pass", None)?;
            c.execute(
                "UPDATE acceptance_items SET status = 'pending', fix_task_id = NULL
                 WHERE status != 'pending' AND fix_task_id IN (
                   SELECT id FROM tasks WHERE source = 'fix' AND status = 'done')
                 AND project_id = ?1",
                [pid],
            )?;
            Ok(())
        })
        .unwrap();
        let item = db
            .read(|c| core_state::acceptance_checklist::get(c, item_id))
            .unwrap()
            .unwrap();
        assert_eq!(item.status, "pass", "重新验收 pass 后不能被联动 SQL 打回");
    }

    #[test]
    fn s10_release_blocked_when_item_not_passed() {
        // S10：空清单或未通过项 → 发布拦截；全部 pass/na → 放行（口径同 request_release）。
        let db = Db::open_in_memory().unwrap();
        let pid = project_fixture(&db);
        // 口径与 request_release 一致（Phase4 C6）：未决>0 或 清单为空 都拦截。
        let blocked = |db: &Db| {
            db.read(|c| {
                let unresolved: i64 = c.query_row(
                    "SELECT COUNT(*) FROM acceptance_items WHERE project_id = ?1 AND status NOT IN ('pass','na')",
                    [pid],
                    |r| r.get(0),
                )?;
                let total: i64 = c.query_row(
                    "SELECT COUNT(*) FROM acceptance_items WHERE project_id = ?1",
                    [pid],
                    |r| r.get(0),
                )?;
                Ok(unresolved > 0 || total == 0)
            })
            .unwrap()
        };
        // 空清单：必须拦截（空清单 ≠ 全部通过）。
        assert!(blocked(&db), "空清单时必须拦截");
        db.write(|c| {
            core_state::acceptance_checklist::regenerate(
                c,
                pid,
                &[
                    core_state::acceptance_checklist::NewAcceptanceItem {
                        source_file: "t.md".into(),
                        tc_id: "TC-01".into(),
                        title: "A".into(),
                        steps: "s".into(),
                        expected: "e".into(),
                        method: "manual".into(),
                        sort_order: 0,
                    },
                    core_state::acceptance_checklist::NewAcceptanceItem {
                        source_file: "t.md".into(),
                        tc_id: "TC-02".into(),
                        title: "B".into(),
                        steps: "s".into(),
                        expected: "e".into(),
                        method: "manual".into(),
                        sort_order: 1,
                    },
                ],
            )?;
            Ok(())
        })
        .unwrap();
        assert!(blocked(&db), "全 pending 时必须拦截");
        let ids = db
            .read(|c| core_state::acceptance_checklist::list(c, pid))
            .unwrap()
            .into_iter()
            .map(|i| i.id)
            .collect::<Vec<_>>();
        db.write(|c| {
            core_state::acceptance_checklist::update_status(c, ids[0], "pass", None)?;
            core_state::acceptance_checklist::update_status(c, ids[1], "na", None)?;
            Ok(())
        })
        .unwrap();
        assert!(!blocked(&db), "pass + na 时放行");
    }
}
