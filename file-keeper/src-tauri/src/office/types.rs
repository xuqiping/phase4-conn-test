use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::error::Error;
use std::fmt::{Debug, Display, Formatter};
use uuid::Uuid;

pub const JS_SAFE_INTEGER_MAX: u64 = 9_007_199_254_740_991;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct OfficeTaskId(Uuid);

impl OfficeTaskId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }

    pub fn parse(value: &str) -> Result<Self, DomainError> {
        Uuid::parse_str(value)
            .map(Self)
            .map_err(|_| DomainError::new(DomainErrorCode::InvalidTaskId))
    }
}

impl Display for OfficeTaskId {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        Display::fmt(&self.0, formatter)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash, Serialize, Deserialize)]
#[serde(transparent)]
pub struct OfficeRequestId(Uuid);

impl OfficeRequestId {
    pub fn new() -> Self {
        Self(Uuid::new_v4())
    }

    pub fn parse(value: &str) -> Result<Self, DomainError> {
        Uuid::parse_str(value)
            .map(Self)
            .map_err(|_| DomainError::new(DomainErrorCode::InvalidRequestId))
    }
}

impl Display for OfficeRequestId {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        Display::fmt(&self.0, formatter)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeTaskStatus {
    Draft,
    Preflight,
    AwaitingConfirmation,
    Queued,
    Running,
    PartialSuccess,
    Succeeded,
    Failed,
    Cancelled,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeEngine {
    OoxmlWorker,
    WindowsOfficeWorker,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OutputPolicy {
    SingleAtomic,
    MultipleIndependent,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeTaskType {
    ExcelSplit,
    ExcelMerge,
    WordBatchReplace,
    PowerPointMerge,
    PowerPointRelink,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum SourceAccess {
    ReadOnly,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum RequestedSourceAccess {
    ReadOnly,
    ReadWrite,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeInputStatus {
    Pending,
    Scanned,
    Ready,
    Failed,
}

#[derive(Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeInput {
    input_id: String,
    /// Local-only path. Domain errors must never copy this value into Display/Debug text.
    path: String,
    format: String,
    size_bytes: u64,
    source_access: SourceAccess,
}

impl OfficeInput {
    pub fn new(
        input_id: impl Into<String>,
        path: impl Into<String>,
        format: impl Into<String>,
        size_bytes: u64,
        requested_access: RequestedSourceAccess,
    ) -> Result<Self, DomainError> {
        if requested_access != RequestedSourceAccess::ReadOnly {
            return Err(DomainError::new(DomainErrorCode::SourceWriteForbidden));
        }
        if size_bytes > JS_SAFE_INTEGER_MAX {
            return Err(DomainError::new(DomainErrorCode::JsSafeIntegerExceeded));
        }

        Ok(Self {
            input_id: input_id.into(),
            path: path.into(),
            format: format.into(),
            size_bytes,
            source_access: SourceAccess::ReadOnly,
        })
    }

    pub const fn source_access(&self) -> SourceAccess {
        self.source_access
    }

    pub const fn size_bytes(&self) -> u64 {
        self.size_bytes
    }

    pub fn input_id(&self) -> &str {
        &self.input_id
    }

    pub fn path(&self) -> &str {
        &self.path
    }

    pub fn format(&self) -> &str {
        &self.format
    }
}

impl Debug for OfficeInput {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeInput")
            .field("input_id", &self.input_id)
            .field("path", &"<redacted>")
            .field("format", &self.format)
            .field("size_bytes", &self.size_bytes)
            .field("source_access", &self.source_access)
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeOutputStatus {
    Planned,
    Validating,
    Published,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeOutput {
    pub output_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_id: Option<String>,
    pub status: OfficeOutputStatus,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeIssueScope {
    Task,
    Input,
    Output,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum OfficeIssueSeverity {
    Info,
    Warning,
    Error,
    Blocking,
}

#[derive(Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeIssue {
    pub issue_id: String,
    pub scope: OfficeIssueScope,
    pub severity: OfficeIssueSeverity,
    pub code: String,
    pub message_key: String,
    /// Structured metadata only; never store document body, passwords, tokens, or model keys.
    pub details_json: Map<String, Value>,
    pub resolved: bool,
}

impl Debug for OfficeIssue {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeIssue")
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

#[derive(Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeTask {
    task_id: OfficeTaskId,
    #[serde(skip_serializing_if = "Option::is_none")]
    request_id: Option<OfficeRequestId>,
    task_type: OfficeTaskType,
    status: OfficeTaskStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    engine: Option<OfficeEngine>,
    output_policy: OutputPolicy,
    inputs: Vec<OfficeInput>,
    outputs: Vec<OfficeOutput>,
    issues: Vec<OfficeIssue>,
}

impl OfficeTask {
    pub fn draft(
        task_id: OfficeTaskId,
        request_id: Option<OfficeRequestId>,
        task_type: OfficeTaskType,
        output_policy: OutputPolicy,
        inputs: Vec<OfficeInput>,
    ) -> Self {
        Self {
            task_id,
            request_id,
            task_type,
            status: OfficeTaskStatus::Draft,
            engine: None,
            output_policy,
            inputs,
            outputs: Vec::new(),
            issues: Vec::new(),
        }
    }
}

impl Debug for OfficeTask {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter
            .debug_struct("OfficeTask")
            .field("task_id", &self.task_id)
            .field("request_id", &self.request_id)
            .field("task_type", &self.task_type)
            .field("status", &self.status)
            .field("engine", &self.engine)
            .field("output_policy", &self.output_policy)
            .field("input_count", &self.inputs.len())
            .field("output_count", &self.outputs.len())
            .field("issue_count", &self.issues.len())
            .finish()
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OutputSummary {
    pub expected: u64,
    pub published: u64,
    pub failed: u64,
}

impl OutputSummary {
    pub const fn new(expected: u64, published: u64, failed: u64) -> Self {
        Self {
            expected,
            published,
            failed,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum DomainErrorCode {
    InvalidTaskId,
    InvalidRequestId,
    InvalidStateTransition,
    SourceWriteForbidden,
    JsSafeIntegerExceeded,
    OutputExpectedInvalid,
    OutputCountOverflow,
    OutputSummaryInconsistent,
    SingleOutputCardinalityInvalid,
    PublicationReceiptTaskMismatch,
}

impl DomainErrorCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidTaskId => "OFFICE_INVALID_TASK_ID",
            Self::InvalidRequestId => "OFFICE_INVALID_REQUEST_ID",
            Self::InvalidStateTransition => "OFFICE_INVALID_STATE_TRANSITION",
            Self::SourceWriteForbidden => "OFFICE_SOURCE_WRITE_FORBIDDEN",
            Self::JsSafeIntegerExceeded => "OFFICE_JS_SAFE_INTEGER_EXCEEDED",
            Self::OutputExpectedInvalid => "OFFICE_OUTPUT_EXPECTED_INVALID",
            Self::OutputCountOverflow => "OFFICE_OUTPUT_COUNT_OVERFLOW",
            Self::OutputSummaryInconsistent => "OFFICE_OUTPUT_SUMMARY_INCONSISTENT",
            Self::SingleOutputCardinalityInvalid => "OFFICE_SINGLE_OUTPUT_CARDINALITY_INVALID",
            Self::PublicationReceiptTaskMismatch => "OFFICE_PUBLICATION_RECEIPT_TASK_MISMATCH",
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct DomainError {
    code: DomainErrorCode,
}

impl DomainError {
    pub const fn new(code: DomainErrorCode) -> Self {
        Self { code }
    }

    pub const fn code(self) -> DomainErrorCode {
        self.code
    }
}

impl Display for DomainError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(self.code.as_str())
    }
}

impl Error for DomainError {}
