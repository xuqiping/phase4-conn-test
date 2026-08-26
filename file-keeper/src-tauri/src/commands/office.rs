use crate::office::credentials::{
    OfficeCredentialReference, OfficeCredentialService, OfficePassword,
};
use crate::office::db::{open_office_task_repository, unix_timestamp_ms};
use crate::office::repository::{
    OfficeInputWriteModel, OfficeIssueWriteModel, OfficeTaskPage, OfficeTaskRepository,
    OfficeTaskSummary, OfficeTaskWriteModel,
};
use crate::office::risk::{OfficeFileFormat, OfficeRiskCode};
use crate::office::scanner::{scan_office_inputs, OfficePreflightScan};
use crate::office::state_machine::{OfficeTaskStateMachine, TaskEvent};
use crate::office::types::{
    OfficeEngine, OfficeInputStatus, OfficeIssueScope, OfficeIssueSeverity, OfficeRequestId,
    OfficeTaskId, OfficeTaskStatus, OfficeTaskType, OutputPolicy,
};
use serde::{Deserialize, Serialize};
use serde_json::Map;
use std::path::{Path, PathBuf};
use std::sync::Mutex;
use tauri::State;
use uuid::Uuid;

const DEFAULT_HISTORY_PAGE_SIZE: u32 = 50;

pub struct OfficeCommandState {
    repository: Mutex<OfficeTaskRepository>,
    credentials: OfficeCredentialService,
}

impl OfficeCommandState {
    pub fn open(database_path: &Path) -> Result<Self, String> {
        let mut repository = open_office_task_repository(database_path)
            .map_err(|error| error.as_str().to_owned())?;
        repository
            .cleanup_default_retention(unix_timestamp_ms())
            .map_err(|error| error.as_str().to_owned())?;
        Ok(Self {
            repository: Mutex::new(repository),
            credentials: OfficeCredentialService::default(),
        })
    }
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeCreateTaskRequest {
    task_type: OfficeTaskType,
    output_policy: OutputPolicy,
    input_paths: Vec<PathBuf>,
    output_directory: PathBuf,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficePreflightInput {
    input_id: String,
    path: String,
    format: OfficeFileFormat,
    size_bytes: u64,
    risks: Vec<OfficeRiskCode>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficePreflightIssue {
    issue_id: String,
    severity: OfficeIssueSeverity,
    code: String,
    message_key: String,
    input_id: Option<String>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficePreflightResponse {
    task_id: OfficeTaskId,
    status: OfficeTaskStatus,
    engine: OfficeEngine,
    output_policy: OutputPolicy,
    output_directory: String,
    inputs: Vec<OfficePreflightInput>,
    issues: Vec<OfficePreflightIssue>,
    within_free_quota: bool,
    can_confirm: bool,
}

#[tauri::command]
pub fn office_create_preflight(
    request: OfficeCreateTaskRequest,
    state: State<'_, OfficeCommandState>,
) -> Result<OfficePreflightResponse, String> {
    if request.input_paths.is_empty() {
        return Err("OFFICE_INPUT_EMPTY".to_owned());
    }
    let scan = scan_office_inputs(
        &request.input_paths,
        &request.input_paths,
        &request.output_directory,
    )
    .map_err(|error| error.as_str().to_owned())?;
    let task_id = OfficeTaskId::new();
    let request_id = OfficeRequestId::new();
    let engine = select_engine(&scan);
    let (response_inputs, write_inputs) = build_inputs(&scan);
    let (response_issues, write_issues) = build_issues(&scan, &response_inputs);
    let can_confirm = !write_issues
        .iter()
        .any(|issue| issue.severity == OfficeIssueSeverity::Blocking);
    let total_bytes = scan.quota.total_bytes;
    let output_directory = scan
        .output_directory
        .as_path()
        .to_string_lossy()
        .into_owned();

    let mut machine = OfficeTaskStateMachine::new(task_id, Some(request_id), request.output_policy);
    machine
        .transition(TaskEvent::BeginPreflight)
        .map_err(|error| error.to_string())?;
    let status = machine
        .transition(TaskEvent::RequireConfirmation)
        .map_err(|error| error.to_string())?;
    let model = OfficeTaskWriteModel {
        task_id,
        request_id: Some(request_id),
        task_type: request.task_type,
        status,
        engine: Some(engine),
        output_policy: request.output_policy,
        rule_schema_version: 1,
        rule_json: Map::new(),
        total_bytes,
        output_dir: Some(output_directory.clone()),
        created_at: unix_timestamp_ms(),
        started_at: None,
        finished_at: None,
        inputs: write_inputs,
        issues: write_issues,
    };
    state
        .repository
        .lock()
        .map_err(|_| "OFFICE_DB_UNAVAILABLE".to_owned())?
        .save_task(&model)
        .map_err(|error| error.as_str().to_owned())?;

    Ok(OfficePreflightResponse {
        task_id,
        status,
        engine,
        output_policy: request.output_policy,
        output_directory,
        inputs: response_inputs,
        issues: response_issues,
        within_free_quota: scan.quota.within_free_quota,
        can_confirm,
    })
}

#[tauri::command]
pub fn office_confirm_task(
    task_id: String,
    state: State<'_, OfficeCommandState>,
) -> Result<OfficeTaskSummary, String> {
    transition_task(task_id, TaskEvent::ConfirmAndQueue, &state)
}

#[tauri::command]
pub fn office_start_task(
    _task_id: String,
    _state: State<'_, OfficeCommandState>,
) -> Result<OfficeTaskSummary, String> {
    Err("OFFICE_EXECUTION_ENGINE_NOT_READY".to_owned())
}

#[tauri::command]
pub fn office_cancel_task(
    task_id: String,
    state: State<'_, OfficeCommandState>,
) -> Result<OfficeTaskSummary, String> {
    transition_task(task_id, TaskEvent::Cancel, &state)
}

#[tauri::command]
pub fn office_list_tasks(
    page: Option<u32>,
    page_size: Option<u32>,
    state: State<'_, OfficeCommandState>,
) -> Result<OfficeTaskPage, String> {
    state
        .repository
        .lock()
        .map_err(|_| "OFFICE_DB_UNAVAILABLE".to_owned())?
        .list_tasks(
            page.unwrap_or(1),
            page_size.unwrap_or(DEFAULT_HISTORY_PAGE_SIZE),
        )
        .map_err(|error| error.as_str().to_owned())
}

#[tauri::command]
pub fn office_recover_tasks(
    state: State<'_, OfficeCommandState>,
) -> Result<Vec<OfficeTaskSummary>, String> {
    state
        .repository
        .lock()
        .map_err(|_| "OFFICE_DB_UNAVAILABLE".to_owned())?
        .list_recoverable_tasks(DEFAULT_HISTORY_PAGE_SIZE)
        .map_err(|error| error.as_str().to_owned())
}

#[tauri::command]
pub fn office_save_credential(
    path: PathBuf,
    password: String,
    state: State<'_, OfficeCommandState>,
) -> Result<OfficeCredentialReference, String> {
    let password = OfficePassword::new(password).map_err(|error| error.to_string())?;
    state
        .credentials
        .save_for_file(&path, password)
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub fn office_delete_credential(
    binding_id: String,
    state: State<'_, OfficeCommandState>,
) -> Result<(), String> {
    let reference =
        OfficeCredentialReference::parse(binding_id).map_err(|error| error.to_string())?;
    state
        .credentials
        .delete(reference)
        .map_err(|error| error.to_string())
}

fn transition_task(
    task_id: String,
    event: TaskEvent,
    state: &State<'_, OfficeCommandState>,
) -> Result<OfficeTaskSummary, String> {
    let task_id = OfficeTaskId::parse(&task_id).map_err(|error| error.to_string())?;
    let mut repository = state
        .repository
        .lock()
        .map_err(|_| "OFFICE_DB_UNAVAILABLE".to_owned())?;
    let current = repository
        .find_task(task_id)
        .map_err(|error| error.as_str().to_owned())?;
    if event == TaskEvent::ConfirmAndQueue
        && repository
            .has_unresolved_blocking_issues(task_id)
            .map_err(|error| error.as_str().to_owned())?
    {
        return Err("OFFICE_BLOCKING_ISSUES_UNRESOLVED".to_owned());
    }
    let mut machine =
        OfficeTaskStateMachine::restore(task_id, None, current.output_policy, current.status);
    let next = machine
        .transition(event)
        .map_err(|error| error.to_string())?;
    let now = unix_timestamp_ms();
    repository
        .update_task_status(
            task_id,
            current.status,
            next,
            (next == OfficeTaskStatus::Running).then_some(now),
            matches!(next, OfficeTaskStatus::Cancelled | OfficeTaskStatus::Failed).then_some(now),
        )
        .map_err(|error| error.as_str().to_owned())?;
    repository
        .find_task(task_id)
        .map_err(|error| error.as_str().to_owned())
}

fn select_engine(scan: &OfficePreflightScan) -> OfficeEngine {
    if scan
        .inputs
        .iter()
        .any(|input| input.assessment.high_fidelity_required)
    {
        OfficeEngine::WindowsOfficeWorker
    } else {
        OfficeEngine::OoxmlWorker
    }
}

fn build_inputs(
    scan: &OfficePreflightScan,
) -> (Vec<OfficePreflightInput>, Vec<OfficeInputWriteModel>) {
    let mut response = Vec::with_capacity(scan.inputs.len());
    let mut write = Vec::with_capacity(scan.inputs.len());
    for input in &scan.inputs {
        let input_id = Uuid::new_v4().to_string();
        let path = input.path().to_string_lossy().into_owned();
        let risks = input.assessment.risks.clone();
        response.push(OfficePreflightInput {
            input_id: input_id.clone(),
            path: path.clone(),
            format: input.format,
            size_bytes: input.size_bytes,
            risks: risks.clone(),
        });
        write.push(OfficeInputWriteModel {
            input_id,
            path,
            fingerprint: input.sha256.clone(),
            format: enum_text(input.format),
            size_bytes: input.size_bytes,
            risk_flags: risks.into_iter().map(enum_text).collect(),
            status: if input.assessment.blocked {
                OfficeInputStatus::Failed
            } else {
                OfficeInputStatus::Ready
            },
            error_code: None,
        });
    }
    (response, write)
}

fn build_issues(
    scan: &OfficePreflightScan,
    inputs: &[OfficePreflightInput],
) -> (Vec<OfficePreflightIssue>, Vec<OfficeIssueWriteModel>) {
    let mut response = Vec::new();
    let mut write = Vec::new();
    for (index, input) in scan.inputs.iter().enumerate() {
        for risk in &input.assessment.risks {
            let code = risk_error_code(*risk).to_owned();
            let severity = if scan.blocking_risks.contains(risk) {
                OfficeIssueSeverity::Blocking
            } else {
                OfficeIssueSeverity::Warning
            };
            push_issue(
                &mut response,
                &mut write,
                severity,
                code,
                format!("office.risk.{}", enum_text(*risk)),
                Some(inputs[index].input_id.clone()),
                OfficeIssueScope::Input,
            );
        }
        if input.assessment.high_fidelity_required {
            push_issue(
                &mut response,
                &mut write,
                OfficeIssueSeverity::Blocking,
                "OFFICE_HIGH_FIDELITY_BLOCKED".to_owned(),
                "office.risk.highFidelityBlocked".to_owned(),
                Some(inputs[index].input_id.clone()),
                OfficeIssueScope::Input,
            );
        }
    }
    if !scan.quota.within_free_quota {
        push_issue(
            &mut response,
            &mut write,
            OfficeIssueSeverity::Warning,
            "OFFICE_PRO_REQUIRED".to_owned(),
            "office.risk.officeProRequired".to_owned(),
            None,
            OfficeIssueScope::Task,
        );
    }
    (response, write)
}

fn push_issue(
    response: &mut Vec<OfficePreflightIssue>,
    write: &mut Vec<OfficeIssueWriteModel>,
    severity: OfficeIssueSeverity,
    code: String,
    message_key: String,
    input_id: Option<String>,
    scope: OfficeIssueScope,
) {
    let issue_id = Uuid::new_v4().to_string();
    response.push(OfficePreflightIssue {
        issue_id: issue_id.clone(),
        severity,
        code: code.clone(),
        message_key: message_key.clone(),
        input_id,
    });
    write.push(OfficeIssueWriteModel {
        issue_id,
        scope,
        severity,
        code,
        message_key,
        details_json: Map::new(),
        resolved: false,
    });
}

fn enum_text<T: Serialize>(value: T) -> String {
    serde_json::to_string(&value)
        .unwrap_or_else(|_| "unknown".to_owned())
        .trim_matches('"')
        .to_owned()
}

fn risk_error_code(risk: OfficeRiskCode) -> &'static str {
    match risk {
        OfficeRiskCode::LegacyBinaryFormat => "OFFICE_LEGACY_BINARY_FORMAT",
        OfficeRiskCode::MacroEnabledFormat => "OFFICE_MACRO_ENABLED_FORMAT",
        OfficeRiskCode::VbaProjectPresent => "OFFICE_VBA_PROJECT_PRESENT",
        OfficeRiskCode::DigitalSignaturePresent => "OFFICE_DIGITAL_SIGNATURE_PRESENT",
        OfficeRiskCode::ExternalRelationshipPresent => "OFFICE_EXTERNAL_RELATIONSHIP_PRESENT",
        OfficeRiskCode::PasswordProtectedPackage => "OFFICE_PASSWORD_PROTECTED_PACKAGE",
        OfficeRiskCode::ReadOnlySource => "OFFICE_READ_ONLY_SOURCE",
        OfficeRiskCode::FileLocked => "OFFICE_FILE_LOCKED",
        OfficeRiskCode::InvalidPackage => "OFFICE_INVALID_PACKAGE",
        OfficeRiskCode::RelationshipTooLarge => "OFFICE_RELATIONSHIP_TOO_LARGE",
    }
}
