use super::output::PublicationReceipt;
use super::types::{
    DomainError, DomainErrorCode, OfficeRequestId, OfficeTaskId, OfficeTaskStatus, OutputPolicy,
    OutputSummary, JS_SAFE_INTEGER_MAX,
};

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
    task_id: OfficeTaskId,
    request_id: Option<OfficeRequestId>,
    output_policy: OutputPolicy,
    status: OfficeTaskStatus,
}

impl OfficeTaskStateMachine {
    pub fn new(
        task_id: OfficeTaskId,
        request_id: Option<OfficeRequestId>,
        output_policy: OutputPolicy,
    ) -> Self {
        Self {
            task_id: task_id.into(),
            request_id,
            output_policy,
            status: OfficeTaskStatus::Draft,
        }
    }

    pub(crate) fn restore(
        task_id: OfficeTaskId,
        request_id: Option<OfficeRequestId>,
        output_policy: OutputPolicy,
        status: OfficeTaskStatus,
    ) -> Self {
        Self {
            task_id,
            request_id,
            output_policy,
            status,
        }
    }

    pub const fn status(&self) -> OfficeTaskStatus {
        self.status
    }

    pub const fn task_id(&self) -> OfficeTaskId {
        self.task_id
    }

    pub const fn request_id(&self) -> Option<OfficeRequestId> {
        self.request_id
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

    pub fn complete_with_publication(
        &mut self,
        receipt: PublicationReceipt,
    ) -> Result<OfficeTaskStatus, DomainError> {
        if self.status != OfficeTaskStatus::Running {
            return Err(DomainError::new(DomainErrorCode::InvalidStateTransition));
        }

        let (receipt_task_id, summary) = receipt.into_parts();
        if receipt_task_id != self.task_id {
            return Err(DomainError::new(
                DomainErrorCode::PublicationReceiptTaskMismatch,
            ));
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
    if summary.expected > JS_SAFE_INTEGER_MAX
        || summary.published > JS_SAFE_INTEGER_MAX
        || summary.failed > JS_SAFE_INTEGER_MAX
    {
        return Err(DomainError::new(DomainErrorCode::JsSafeIntegerExceeded));
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
