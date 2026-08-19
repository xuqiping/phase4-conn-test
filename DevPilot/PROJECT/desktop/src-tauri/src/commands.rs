//! IPC commands：前端操作状态机的唯一入口（单一真相源在 Rust，plan 坑点表）。
//! 错误上抛只带大白话 message + 分类 code，不含路径/堆栈（plan 安全清单）。

use core_state::machine::loader;
use core_state::machine::{PersistentMachine, TransitionError};
use core_state::Db;
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
