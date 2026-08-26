use super::types::{
    OfficeEngine, OfficeInputStatus, OfficeIssueScope, OfficeIssueSeverity, OfficeRequestId,
    OfficeTaskId, OfficeTaskStatus, OfficeTaskType, OutputPolicy, JS_SAFE_INTEGER_MAX,
};
use rusqlite::{params, Connection, OptionalExtension};
use serde::{de::DeserializeOwned, Serialize};
use serde_json::{Map, Value};
use std::error::Error;
use std::fmt::{Display, Formatter};

const MAX_PAGE_SIZE: u32 = 200;
const DEFAULT_RETENTION_DAYS: i64 = 90;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum OfficeRepositoryError {
    DatabaseUnavailable,
    MigrationFailed,
    BackupFailed,
    InvalidPage,
    InvalidCount,
    InvalidJson,
    SensitiveDataRejected,
    RecordNotFound,
}

impl OfficeRepositoryError {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::DatabaseUnavailable => "OFFICE_DB_UNAVAILABLE",
            Self::MigrationFailed => "OFFICE_DB_MIGRATION_FAILED",
            Self::BackupFailed => "OFFICE_DB_BACKUP_FAILED",
            Self::InvalidPage => "OFFICE_DB_INVALID_PAGE",
            Self::InvalidCount => "OFFICE_DB_INVALID_COUNT",
            Self::InvalidJson => "OFFICE_DB_INVALID_JSON",
            Self::SensitiveDataRejected => "OFFICE_DB_SENSITIVE_DATA_REJECTED",
            Self::RecordNotFound => "OFFICE_DB_RECORD_NOT_FOUND",
        }
    }
}

impl Display for OfficeRepositoryError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.as_str())
    }
}

impl Error for OfficeRepositoryError {}

#[derive(Clone)]
pub struct OfficeTaskWriteModel {
    pub task_id: OfficeTaskId,
    pub request_id: Option<OfficeRequestId>,
    pub task_type: OfficeTaskType,
    pub status: OfficeTaskStatus,
    pub engine: Option<OfficeEngine>,
    pub output_policy: OutputPolicy,
    pub rule_schema_version: u32,
    pub rule_json: Map<String, Value>,
    pub total_bytes: u64,
    pub output_dir: Option<String>,
    pub created_at: i64,
    pub started_at: Option<i64>,
    pub finished_at: Option<i64>,
    pub inputs: Vec<OfficeInputWriteModel>,
    pub issues: Vec<OfficeIssueWriteModel>,
}

impl std::fmt::Debug for OfficeTaskWriteModel {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeTaskWriteModel")
            .field("task_id", &self.task_id)
            .field("request_id", &self.request_id)
            .field("task_type", &self.task_type)
            .field("status", &self.status)
            .field("engine", &self.engine)
            .field("output_policy", &self.output_policy)
            .field("rule_schema_version", &self.rule_schema_version)
            .field("rule_json", &"<redacted>")
            .field("total_bytes", &self.total_bytes)
            .field("output_dir", &"<redacted>")
            .field("created_at", &self.created_at)
            .field("started_at", &self.started_at)
            .field("finished_at", &self.finished_at)
            .field("input_count", &self.inputs.len())
            .field("issue_count", &self.issues.len())
            .finish()
    }
}

#[derive(Clone)]
pub struct OfficeInputWriteModel {
    pub input_id: String,
    pub path: String,
    pub fingerprint: String,
    pub format: String,
    pub size_bytes: u64,
    pub risk_flags: Vec<String>,
    pub status: OfficeInputStatus,
    pub error_code: Option<String>,
}

impl std::fmt::Debug for OfficeInputWriteModel {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeInputWriteModel")
            .field("input_id", &self.input_id)
            .field("path", &"<redacted>")
            .field("fingerprint", &self.fingerprint)
            .field("format", &self.format)
            .field("size_bytes", &self.size_bytes)
            .field("risk_flags", &self.risk_flags)
            .field("status", &self.status)
            .field("error_code", &self.error_code)
            .finish()
    }
}

#[derive(Clone)]
pub struct OfficeIssueWriteModel {
    pub issue_id: String,
    pub scope: OfficeIssueScope,
    pub severity: OfficeIssueSeverity,
    pub code: String,
    pub message_key: String,
    pub details_json: Map<String, Value>,
    pub resolved: bool,
}

impl std::fmt::Debug for OfficeIssueWriteModel {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeIssueWriteModel")
            .field("issue_id", &self.issue_id)
            .field("scope", &self.scope)
            .field("severity", &self.severity)
            .field("code", &self.code)
            .field("message_key", &self.message_key)
            .field("details_json", &"<redacted>")
            .field("resolved", &self.resolved)
            .finish()
    }
}

#[derive(Clone, PartialEq, Eq)]
pub struct OfficeTaskSummary {
    pub task_id: OfficeTaskId,
    pub task_type: OfficeTaskType,
    pub status: OfficeTaskStatus,
    pub engine: Option<OfficeEngine>,
    pub output_policy: OutputPolicy,
    pub input_count: u64,
    pub total_bytes: u64,
    pub output_dir: Option<String>,
    pub created_at: i64,
    pub started_at: Option<i64>,
    pub finished_at: Option<i64>,
}

impl std::fmt::Debug for OfficeTaskSummary {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeTaskSummary")
            .field("task_id", &self.task_id)
            .field("task_type", &self.task_type)
            .field("status", &self.status)
            .field("engine", &self.engine)
            .field("output_policy", &self.output_policy)
            .field("input_count", &self.input_count)
            .field("total_bytes", &self.total_bytes)
            .field("output_dir", &"<redacted>")
            .field("created_at", &self.created_at)
            .field("started_at", &self.started_at)
            .field("finished_at", &self.finished_at)
            .finish()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfficeTaskPage {
    pub items: Vec<OfficeTaskSummary>,
    pub total: u64,
    pub page: u32,
    pub page_size: u32,
}

pub struct OfficeTaskRepository {
    connection: Connection,
}

impl OfficeTaskRepository {
    pub(crate) fn new(connection: Connection) -> Self {
        Self { connection }
    }

    pub fn save_task(&mut self, model: &OfficeTaskWriteModel) -> Result<(), OfficeRepositoryError> {
        validate_write_model(model)?;
        let rule_json = serialize_json(&model.rule_json)?;
        let transaction = self
            .connection
            .transaction()
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;

        transaction
            .execute(
                r#"INSERT INTO office_tasks(
                    id, request_id, task_type, status, engine, output_policy,
                    rule_schema_version, rule_json, input_count, total_bytes,
                    output_dir, created_at, started_at, finished_at
                ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14)
                ON CONFLICT(id) DO UPDATE SET
                    request_id=excluded.request_id,
                    task_type=excluded.task_type,
                    status=excluded.status,
                    engine=excluded.engine,
                    output_policy=excluded.output_policy,
                    rule_schema_version=excluded.rule_schema_version,
                    rule_json=excluded.rule_json,
                    input_count=excluded.input_count,
                    total_bytes=excluded.total_bytes,
                    output_dir=excluded.output_dir,
                    started_at=excluded.started_at,
                    finished_at=excluded.finished_at"#,
                params![
                    model.task_id.to_string(),
                    model.request_id.map(|value| value.to_string()),
                    enum_to_db(&model.task_type)?,
                    enum_to_db(&model.status)?,
                    model.engine.as_ref().map(enum_to_db).transpose()?,
                    enum_to_db(&model.output_policy)?,
                    i64::from(model.rule_schema_version),
                    rule_json,
                    model.inputs.len() as i64,
                    model.total_bytes as i64,
                    model.output_dir.as_deref(),
                    model.created_at,
                    model.started_at,
                    model.finished_at,
                ],
            )
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;

        transaction
            .execute(
                "DELETE FROM office_task_inputs WHERE task_id = ?1",
                [model.task_id.to_string()],
            )
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        transaction
            .execute(
                "DELETE FROM office_task_issues WHERE task_id = ?1",
                [model.task_id.to_string()],
            )
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;

        for input in &model.inputs {
            transaction
                .execute(
                    r#"INSERT INTO office_task_inputs(
                        task_id, input_id, path, fingerprint, format, size_bytes,
                        risk_flags_json, status, error_code
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9)"#,
                    params![
                        model.task_id.to_string(),
                        input.input_id.as_str(),
                        input.path.as_str(),
                        input.fingerprint.as_str(),
                        input.format.as_str(),
                        input.size_bytes as i64,
                        serialize_json(&input.risk_flags)?,
                        enum_to_db(&input.status)?,
                        input.error_code.as_deref(),
                    ],
                )
                .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        }

        for issue in &model.issues {
            transaction
                .execute(
                    r#"INSERT INTO office_task_issues(
                        task_id, issue_id, scope, severity, code, message_key, details_json, resolved
                    ) VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)"#,
                    params![
                        model.task_id.to_string(),
                        issue.issue_id.as_str(),
                        enum_to_db(&issue.scope)?,
                        enum_to_db(&issue.severity)?,
                        issue.code.as_str(),
                        issue.message_key.as_str(),
                        serialize_json(&issue.details_json)?,
                        if issue.resolved { 1_i64 } else { 0_i64 },
                    ],
                )
                .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        }

        transaction
            .commit()
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)
    }

    pub fn list_tasks(
        &self,
        page: u32,
        page_size: u32,
    ) -> Result<OfficeTaskPage, OfficeRepositoryError> {
        if page == 0 || page_size == 0 || page_size > MAX_PAGE_SIZE {
            return Err(OfficeRepositoryError::InvalidPage);
        }
        let total: i64 = self
            .connection
            .query_row("SELECT COUNT(*) FROM office_tasks", [], |row| row.get(0))
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        let offset = i64::from(page - 1) * i64::from(page_size);
        let mut statement = self
            .connection
            .prepare(
                r#"SELECT id, task_type, status, engine, output_policy, input_count,
                    total_bytes, output_dir, created_at, started_at, finished_at
                    FROM office_tasks
                    ORDER BY created_at DESC, id
                    LIMIT ?1 OFFSET ?2"#,
            )
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        let rows = statement
            .query_map(params![i64::from(page_size), offset], map_summary_row)
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        let items = rows
            .collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|_| OfficeRepositoryError::InvalidJson)?;
        Ok(OfficeTaskPage {
            items,
            total: total.max(0) as u64,
            page,
            page_size,
        })
    }

    pub fn list_recoverable_tasks(
        &self,
        limit: u32,
    ) -> Result<Vec<OfficeTaskSummary>, OfficeRepositoryError> {
        if limit == 0 || limit > MAX_PAGE_SIZE {
            return Err(OfficeRepositoryError::InvalidPage);
        }
        let recoverable = [
            OfficeTaskStatus::Preflight,
            OfficeTaskStatus::AwaitingConfirmation,
            OfficeTaskStatus::Queued,
            OfficeTaskStatus::Running,
        ];
        let mut statement = self
            .connection
            .prepare(
                r#"SELECT id, task_type, status, engine, output_policy, input_count,
                    total_bytes, output_dir, created_at, started_at, finished_at
                    FROM office_tasks
                    WHERE status IN (?1, ?2, ?3, ?4)
                    ORDER BY created_at ASC, id
                    LIMIT ?5"#,
            )
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        let rows = statement
            .query_map(
                params![
                    enum_to_db(&recoverable[0])?,
                    enum_to_db(&recoverable[1])?,
                    enum_to_db(&recoverable[2])?,
                    enum_to_db(&recoverable[3])?,
                    i64::from(limit),
                ],
                map_summary_row,
            )
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)?;
        rows.collect::<rusqlite::Result<Vec<_>>>()
            .map_err(|_| OfficeRepositoryError::InvalidJson)
    }

    pub fn find_task(
        &self,
        task_id: OfficeTaskId,
    ) -> Result<OfficeTaskSummary, OfficeRepositoryError> {
        self.connection
            .query_row(
                r#"SELECT id, task_type, status, engine, output_policy, input_count,
                    total_bytes, output_dir, created_at, started_at, finished_at
                    FROM office_tasks WHERE id = ?1"#,
                [task_id.to_string()],
                map_summary_row,
            )
            .optional()
            .map_err(|_| OfficeRepositoryError::InvalidJson)?
            .ok_or(OfficeRepositoryError::RecordNotFound)
    }

    pub fn cleanup_finished_before(
        &mut self,
        cutoff_timestamp: i64,
    ) -> Result<u64, OfficeRepositoryError> {
        self.connection
            .execute(
                r#"DELETE FROM office_tasks
                    WHERE finished_at IS NOT NULL AND finished_at < ?1
                    AND status IN (?2, ?3, ?4, ?5)"#,
                params![
                    cutoff_timestamp,
                    enum_to_db(&OfficeTaskStatus::Succeeded)?,
                    enum_to_db(&OfficeTaskStatus::PartialSuccess)?,
                    enum_to_db(&OfficeTaskStatus::Failed)?,
                    enum_to_db(&OfficeTaskStatus::Cancelled)?,
                ],
            )
            .map(|count| count as u64)
            .map_err(|_| OfficeRepositoryError::DatabaseUnavailable)
    }

    pub fn cleanup_default_retention(
        &mut self,
        now_timestamp: i64,
    ) -> Result<u64, OfficeRepositoryError> {
        let retention_ms = DEFAULT_RETENTION_DAYS * 24 * 60 * 60 * 1000;
        self.cleanup_finished_before(now_timestamp.saturating_sub(retention_ms))
    }
}

fn validate_write_model(model: &OfficeTaskWriteModel) -> Result<(), OfficeRepositoryError> {
    if model.rule_schema_version == 0
        || model.total_bytes > JS_SAFE_INTEGER_MAX
        || model.inputs.len() as u64 > JS_SAFE_INTEGER_MAX
        || model
            .inputs
            .iter()
            .any(|input| input.size_bytes > JS_SAFE_INTEGER_MAX)
    {
        return Err(OfficeRepositoryError::InvalidCount);
    }
    reject_sensitive_json(&Value::Object(model.rule_json.clone()))?;
    for issue in &model.issues {
        reject_sensitive_json(&Value::Object(issue.details_json.clone()))?;
    }
    Ok(())
}

fn reject_sensitive_json(value: &Value) -> Result<(), OfficeRepositoryError> {
    match value {
        Value::Object(map) => {
            for (key, child) in map {
                let normalized = key
                    .chars()
                    .filter(|character| character.is_ascii_alphanumeric())
                    .flat_map(char::to_lowercase)
                    .collect::<String>();
                if [
                    "password",
                    "apikey",
                    "authorization",
                    "accesstoken",
                    "refreshtoken",
                    "documentbody",
                    "rawdocumenttext",
                    "prompt",
                ]
                .iter()
                .any(|forbidden| normalized.contains(forbidden))
                {
                    return Err(OfficeRepositoryError::SensitiveDataRejected);
                }
                reject_sensitive_json(child)?;
            }
        }
        Value::Array(items) => {
            for item in items {
                reject_sensitive_json(item)?;
            }
        }
        _ => {}
    }
    Ok(())
}

fn enum_to_db<T: Serialize>(value: &T) -> Result<String, OfficeRepositoryError> {
    serde_json::to_string(value)
        .map(|encoded| encoded.trim_matches('"').to_owned())
        .map_err(|_| OfficeRepositoryError::InvalidJson)
}

fn enum_from_db<T: DeserializeOwned>(value: String) -> rusqlite::Result<T> {
    serde_json::from_str(&format!("\"{value}\"")).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, Box::new(error))
    })
}

fn serialize_json<T: Serialize>(value: &T) -> Result<String, OfficeRepositoryError> {
    serde_json::to_string(value).map_err(|_| OfficeRepositoryError::InvalidJson)
}

fn map_summary_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<OfficeTaskSummary> {
    let engine: Option<String> = row.get(3)?;
    let input_count: i64 = row.get(5)?;
    let total_bytes: i64 = row.get(6)?;
    if input_count < 0
        || total_bytes < 0
        || input_count as u64 > JS_SAFE_INTEGER_MAX
        || total_bytes as u64 > JS_SAFE_INTEGER_MAX
    {
        return Err(rusqlite::Error::IntegralValueOutOfRange(5, input_count));
    }
    let task_id_text: String = row.get(0)?;
    let task_id = OfficeTaskId::parse(&task_id_text).map_err(|error| {
        rusqlite::Error::FromSqlConversionFailure(0, rusqlite::types::Type::Text, Box::new(error))
    })?;
    Ok(OfficeTaskSummary {
        task_id,
        task_type: enum_from_db(row.get(1)?)?,
        status: enum_from_db(row.get(2)?)?,
        engine: engine.map(enum_from_db).transpose()?,
        output_policy: enum_from_db(row.get(4)?)?,
        input_count: input_count as u64,
        total_bytes: total_bytes as u64,
        output_dir: row.get(7)?,
        created_at: row.get(8)?,
        started_at: row.get(9)?,
        finished_at: row.get(10)?,
    })
}
