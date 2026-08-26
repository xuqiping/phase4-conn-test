use serde::{Deserialize, Serialize};
use std::path::PathBuf;

pub const OFFICE_WORKER_PROTOCOL_VERSION: u16 = 1;
pub const OFFICE_WORKER_LINE_MAX_BYTES: usize = 1024 * 1024;

#[derive(Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WorkerOperation {
    Handshake,
    Inspect,
    Heartbeat,
    Cancel,
    Shutdown,
    #[serde(other)]
    Unsupported,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WorkerEventKind {
    Ready,
    Heartbeat,
    Progress,
    Result,
    Cancelled,
    ShuttingDown,
    Error,
}

#[derive(Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerRequest {
    pub request_id: String,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    pub operation: WorkerOperation,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub path: Option<PathBuf>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub target_request_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_version: Option<u16>,
}

impl WorkerRequest {
    #[allow(dead_code)]
    pub fn control(request_id: String, operation: WorkerOperation) -> Self {
        Self {
            request_id,
            task_id: None,
            operation,
            path: None,
            target_request_id: None,
            protocol_version: Some(OFFICE_WORKER_PROTOCOL_VERSION),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub enum WorkerProgressPhase {
    Inspect,
    Prepare,
    Execute,
    Validate,
    Publish,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerProgress {
    pub phase: WorkerProgressPhase,
    pub completed: u64,
    pub total: u64,
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct WorkerResponse {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub request_id: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub task_id: Option<String>,
    pub event: WorkerEventKind,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub protocol_version: Option<u16>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub worker_pid: Option<u32>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub progress: Option<WorkerProgress>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub classification: Option<String>,
    #[serde(default)]
    pub risk_codes: Vec<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error_code: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub source_sha256: Option<String>,
}

impl WorkerResponse {
    pub fn control(
        request_id: Option<String>,
        event: WorkerEventKind,
        worker_pid: Option<u32>,
    ) -> Self {
        Self {
            request_id,
            task_id: None,
            event,
            protocol_version: Some(OFFICE_WORKER_PROTOCOL_VERSION),
            worker_pid,
            progress: None,
            classification: None,
            risk_codes: Vec::new(),
            error_code: None,
            source_sha256: None,
        }
    }

    pub fn blocked(request_id: Option<String>, error_code: &'static str) -> Self {
        Self {
            request_id,
            task_id: None,
            event: WorkerEventKind::Error,
            protocol_version: Some(OFFICE_WORKER_PROTOCOL_VERSION),
            worker_pid: None,
            progress: None,
            classification: Some("BLOCKED".to_string()),
            risk_codes: Vec::new(),
            error_code: Some(error_code.to_string()),
            source_sha256: None,
        }
    }
}
