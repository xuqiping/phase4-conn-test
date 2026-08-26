use serde::{Deserialize, Serialize};
use serde_json::{Map, Value};
use std::error::Error;
use std::fmt::{Display, Formatter};

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

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeInput {
    pub input_id: String,
    /// Local-only path. Domain errors must never copy this value into Display/Debug text.
    pub path: String,
    pub format: String,
    pub size_bytes: u64,
    pub source_access: SourceAccess,
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

        Ok(Self {
            input_id: input_id.into(),
            path: path.into(),
            format: format.into(),
            size_bytes,
            source_access: SourceAccess::ReadOnly,
        })
    }
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeOutput {
    pub output_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub input_id: Option<String>,
    pub status: String,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeIssue {
    pub issue_id: String,
    pub scope: String,
    pub severity: String,
    pub code: String,
    pub message_key: String,
    /// Structured metadata only; never store document body, passwords, tokens, or model keys.
    pub details_json: Map<String, Value>,
    pub resolved: bool,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct OfficeTask {
    pub task_id: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    pub task_type: OfficeTaskType,
    pub status: OfficeTaskStatus,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub engine: Option<OfficeEngine>,
    pub output_policy: OutputPolicy,
    pub inputs: Vec<OfficeInput>,
    pub outputs: Vec<OfficeOutput>,
    pub issues: Vec<OfficeIssue>,
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
    InvalidStateTransition,
    SourceWriteForbidden,
    OutputExpectedInvalid,
    OutputCountOverflow,
    OutputSummaryInconsistent,
    SingleOutputCardinalityInvalid,
}

impl DomainErrorCode {
    pub const fn as_str(self) -> &'static str {
        match self {
            Self::InvalidStateTransition => "OFFICE_INVALID_STATE_TRANSITION",
            Self::SourceWriteForbidden => "OFFICE_SOURCE_WRITE_FORBIDDEN",
            Self::OutputExpectedInvalid => "OFFICE_OUTPUT_EXPECTED_INVALID",
            Self::OutputCountOverflow => "OFFICE_OUTPUT_COUNT_OVERFLOW",
            Self::OutputSummaryInconsistent => "OFFICE_OUTPUT_SUMMARY_INCONSISTENT",
            Self::SingleOutputCardinalityInvalid => "OFFICE_SINGLE_OUTPUT_CARDINALITY_INVALID",
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
