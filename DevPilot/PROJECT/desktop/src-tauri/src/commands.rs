//! IPC commands：前端操作状态机的唯一入口（单一真相源在 Rust，plan 坑点表）。
//! 错误上抛只带大白话 message + 分类 code，不含路径/堆栈（plan 安全清单）。

use core_state::agent_config::AgentConfigFields;
use core_state::machine::loader;
use core_state::machine::{PersistentMachine, TransitionError};
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
            let r = tokio::runtime::Handle::current().block_on(core_exec::run_task(
                Some(c),
                &req,
                std::path::Path::new(&path),
                &core_exec::NoOpFixStrategy,
                &secrets,
                |_line| {
                    // 后续可接前端事件流（FR-038 日志透明层）
                },
            ));
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

    // 创建任务记录。
    let task_id: i64 = state
        .db
        .write(|c| {
            c.execute(
                "INSERT INTO tasks (round_id, chunk_no, title, status, source) VALUES (?1, 1, 'build', 'running', 'local')",
                [round_id],
            )?;
            Ok(c.last_insert_rowid())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    let path = project_path(&state.db, project_id)?;
    let result = tokio::task::block_in_place(|| {
        state.db.write(|c| {
            let names = core_state::secrets::list(c, project_id)?;
            let mut secrets = Vec::new();
            for m in names {
                if let Some(v) = core_state::secrets::load(c, project_id, &m.name)? {
                    secrets.push(core_exec::MaskedSecret::new(m.name, v));
                }
            }
            let req = core_exec::TaskRequest {
                project_id,
                task_id,
                title: "自动构建".into(),
                instructions: "执行本地测试/lint 并提交存档点".into(),
                files: Vec::new(),
                test_command: None,
                max_fix_attempts: 0,
            };
            let r = tokio::runtime::Handle::current().block_on(core_exec::run_task(
                Some(c),
                &req,
                std::path::Path::new(&path),
                &core_exec::NoOpFixStrategy,
                &secrets,
                |_line| {},
            ));
            Ok::<_, core_state::DbError>(r)
        })
    })
    .map_err(|e| err("DB", e.to_string()))?;

    // 回写任务结果与 checkpoint。
    let status = if result.success { "done" } else { "failed" };
    let git_commit = latest_commit_hash(&path).unwrap_or_default();
    state
        .db
        .write(|c| {
            c.execute(
                "UPDATE tasks SET status = ?1, tokens_actual = ?2, updated_at = datetime('now') WHERE id = ?3",
                (status, result.cost_cents, task_id),
            )?;
            c.execute(
                "INSERT INTO checkpoints (task_id, git_commit, summary_plain) VALUES (?1, ?2, ?3)",
                (task_id, &git_commit, &result.diff_summary),
            )?;
            Ok(())
        })
        .map_err(|e| err("DB", e.to_string()))?;

    // 成功则推进到验收阶段。
    let mut warning: Option<String> = None;
    if result.success {
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
    } else {
        warning = Some(format!(
            "构建失败（exit={:?}），已保留 devpilot-failed 存档点",
            result.test_result.as_ref().and_then(|t| t.exit_code)
        ));
    }

    let dto = to_state_dto(
        &machine,
        warning.or_else(|| {
            if result.success {
                None
            } else {
                Some("构建未通过，留在建造阶段".into())
            }
        }),
    );
    events::emit_state(&app, &dto);
    Ok(dto)
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

fn latest_commit_hash(project_path: &str) -> Option<String> {
    std::process::Command::new("git")
        .args(["log", "-1", "--pretty=%H"])
        .current_dir(project_path)
        .output()
        .ok()
        .filter(|o| o.status.success())
        .map(|o| String::from_utf8_lossy(&o.stdout).trim().to_string())
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
}
