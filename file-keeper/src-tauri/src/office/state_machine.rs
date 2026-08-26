use super::types::{DomainError, DomainErrorCode, OfficeTaskStatus, OutputPolicy, OutputSummary};

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TaskEvent {
    BeginPreflight,
    RequireConfirmation,
    ConfirmAndQueue,
    Start,
    Fail,
    Cancel,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct OfficeTaskStateMachine {
    task_id: String,
    request_id: Option<String>,
    output_policy: OutputPolicy,
    status: OfficeTaskStatus,
}

impl OfficeTaskStateMachine {
    pub fn new(
        task_id: impl Into<String>,
        request_id: Option<String>,
        output_policy: OutputPolicy,
    ) -> Self {
        Self {
            task_id: task_id.into(),
            request_id,
            output_policy,
            status: OfficeTaskStatus::Draft,
        }
    }

    pub const fn status(&self) -> OfficeTaskStatus {
        self.status
    }

    pub fn task_id(&self) -> &str {
        &self.task_id
    }

    pub fn request_id(&self) -> Option<&str> {
        self.request_id.as_deref()
    }

    pub fn transition(&mut self, event: TaskEvent) -> Result<OfficeTaskStatus, DomainError> {
        let next = match (self.status, event) {
            (OfficeTaskStatus::Draft, TaskEvent::BeginPreflight) => OfficeTaskStatus::Preflight,
            (OfficeTaskStatus::Preflight, TaskEvent::RequireConfirmation) => {
                OfficeTaskStatus::AwaitingConfirmation
            }
            (OfficeTaskStatus::AwaitingConfirmation, TaskEvent::ConfirmAndQueue) => {
                OfficeTaskStatus::Queued
            }
            (OfficeTaskStatus::Queued, TaskEvent::Start) => OfficeTaskStatus::Running,
            (OfficeTaskStatus::Preflight, TaskEvent::Fail)
            | (OfficeTaskStatus::Queued, TaskEvent::Fail)
            | (OfficeTaskStatus::Running, TaskEvent::Fail) => OfficeTaskStatus::Failed,
            (
                OfficeTaskStatus::Draft
                | OfficeTaskStatus::Preflight
                | OfficeTaskStatus::AwaitingConfirmation
                | OfficeTaskStatus::Queued
                | OfficeTaskStatus::Running,
                TaskEvent::Cancel,
            ) => OfficeTaskStatus::Cancelled,
            _ => return Err(DomainError::new(DomainErrorCode::InvalidStateTransition)),
        };

        self.status = next;
        Ok(next)
    }

    pub fn complete(&mut self, summary: OutputSummary) -> Result<OfficeTaskStatus, DomainError> {
        if self.status != OfficeTaskStatus::Running {
            return Err(DomainError::new(DomainErrorCode::InvalidStateTransition));
        }

        let next = derive_completion_status(self.output_policy, summary)?;
        self.status = next;
        Ok(next)
    }
}

fn derive_completion_status(
    output_policy: OutputPolicy,
    summary: OutputSummary,
) -> Result<OfficeTaskStatus, DomainError> {
    if summary.expected == 0 {
        return Err(DomainError::new(DomainErrorCode::OutputExpectedInvalid));
    }

    let accounted = summary
        .published
        .checked_add(summary.failed)
        .ok_or_else(|| DomainError::new(DomainErrorCode::OutputCountOverflow))?;
    if accounted != summary.expected {
        return Err(DomainError::new(DomainErrorCode::OutputSummaryInconsistent));
    }

    match output_policy {
        OutputPolicy::SingleAtomic => {
            if summary.expected != 1 {
                return Err(DomainError::new(
                    DomainErrorCode::SingleOutputCardinalityInvalid,
                ));
            }
            if summary.published == 1 && summary.failed == 0 {
                Ok(OfficeTaskStatus::Succeeded)
            } else if summary.published == 0 && summary.failed == 1 {
                Ok(OfficeTaskStatus::Failed)
            } else {
                Err(DomainError::new(DomainErrorCode::OutputSummaryInconsistent))
            }
        }
        OutputPolicy::MultipleIndependent => {
            if summary.published == summary.expected {
                Ok(OfficeTaskStatus::Succeeded)
            } else if summary.failed == summary.expected {
                Ok(OfficeTaskStatus::Failed)
            } else {
                Ok(OfficeTaskStatus::PartialSuccess)
            }
        }
    }
}
