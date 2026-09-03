use super::output::{OutputTransaction, PublicationReceipt};
use super::state_machine::{derive_completion_status, OfficeTaskStateMachine, TaskEvent};
use super::types::{
    DomainErrorCode, OfficeInput, OfficeIssue, OfficeIssueScope, OfficeIssueSeverity, OfficeOutput,
    OfficeOutputStatus, OfficeRequestId, OfficeTask, OfficeTaskId, OfficeTaskStatus,
    OfficeTaskType, OutputPolicy, OutputSummary, RequestedSourceAccess, SourceAccess,
    JS_SAFE_INTEGER_MAX,
};
use std::fs;

fn task_id() -> OfficeTaskId {
    OfficeTaskId::parse("00000000-0000-4000-8000-000000000001").unwrap()
}

fn request_id() -> OfficeRequestId {
    OfficeRequestId::parse("00000000-0000-4000-8000-000000000002").unwrap()
}

fn other_task_id() -> OfficeTaskId {
    OfficeTaskId::parse("00000000-0000-4000-8000-000000000003").unwrap()
}

fn publication_receipt(
    receipt_task_id: OfficeTaskId,
    output_policy: OutputPolicy,
    summary: OutputSummary,
) -> PublicationReceipt {
    let root = std::env::temp_dir().join(format!(
        "file-keeper-state-machine-test-{}",
        uuid::Uuid::new_v4()
    ));
    fs::create_dir_all(&root).unwrap();
    let mut transaction =
        OutputTransaction::start(receipt_task_id, output_policy, &root, 1, summary.expected)
            .unwrap();

    for index in 0..summary.published {
        let output_id = format!("published-{index}");
        let staged = transaction.stage(&output_id).unwrap();
        fs::write(staged.path(), b"test-output").unwrap();
        transaction
            .publish(staged, &format!("output-{index}.csv"), |_| Ok(()))
            .unwrap();
    }
    for index in 0..summary.failed {
        transaction
            .record_failure(&format!("failed-{index}"))
            .unwrap();
    }

    let receipt = transaction.finalize().unwrap();
    fs::remove_dir_all(root).unwrap();
    receipt
}

fn single_output_task() -> OfficeTaskStateMachine {
    OfficeTaskStateMachine::new(task_id(), Some(request_id()), OutputPolicy::SingleAtomic)
}

fn advance_to_running(task: &mut OfficeTaskStateMachine) {
    task.transition(TaskEvent::BeginPreflight).unwrap();
    task.transition(TaskEvent::RequireConfirmation).unwrap();
    task.transition(TaskEvent::ConfirmAndQueue).unwrap();
    task.transition(TaskEvent::Start).unwrap();
}

#[test]
fn follows_the_confirmed_happy_path_to_running() {
    let mut task = single_output_task();

    assert_eq!(task.task_id(), task_id());
    assert_eq!(task.request_id(), Some(request_id()));
    assert_eq!(task.status(), OfficeTaskStatus::Draft);
    task.transition(TaskEvent::BeginPreflight).unwrap();
    assert_eq!(task.status(), OfficeTaskStatus::Preflight);
    task.transition(TaskEvent::RequireConfirmation).unwrap();
    assert_eq!(task.status(), OfficeTaskStatus::AwaitingConfirmation);
    task.transition(TaskEvent::ConfirmAndQueue).unwrap();
    assert_eq!(task.status(), OfficeTaskStatus::Queued);
    task.transition(TaskEvent::Start).unwrap();
    assert_eq!(task.status(), OfficeTaskStatus::Running);
}

#[test]
fn rejects_starting_directly_from_draft() {
    let mut task = single_output_task();

    let error = task.transition(TaskEvent::Start).unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::InvalidStateTransition);
    assert_eq!(task.status(), OfficeTaskStatus::Draft);
}

#[test]
fn rejects_execution_before_user_confirmation() {
    let mut task = single_output_task();
    task.transition(TaskEvent::BeginPreflight).unwrap();

    let error = task.transition(TaskEvent::Start).unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::InvalidStateTransition);
    assert_eq!(task.status(), OfficeTaskStatus::Preflight);
}

#[test]
fn preflight_can_fail_and_failed_task_cannot_be_published() {
    let mut task = single_output_task();
    task.transition(TaskEvent::BeginPreflight).unwrap();
    task.transition(TaskEvent::Fail).unwrap();

    assert_eq!(task.status(), OfficeTaskStatus::Failed);
    let error = task
        .complete_with_publication(publication_receipt(
            task_id(),
            OutputPolicy::SingleAtomic,
            OutputSummary::new(1, 1, 0),
        ))
        .unwrap_err();
    assert_eq!(error.code(), DomainErrorCode::InvalidStateTransition);
}

#[test]
fn queued_task_can_fail_before_worker_execution_begins() {
    let mut task = single_output_task();
    task.transition(TaskEvent::BeginPreflight).unwrap();
    task.transition(TaskEvent::RequireConfirmation).unwrap();
    task.transition(TaskEvent::ConfirmAndQueue).unwrap();

    task.transition(TaskEvent::Fail).unwrap();

    assert_eq!(task.status(), OfficeTaskStatus::Failed);
}

#[test]
fn confirmation_and_running_stages_can_be_cancelled() {
    let mut awaiting_confirmation = single_output_task();
    awaiting_confirmation
        .transition(TaskEvent::BeginPreflight)
        .unwrap();
    awaiting_confirmation
        .transition(TaskEvent::RequireConfirmation)
        .unwrap();
    awaiting_confirmation.transition(TaskEvent::Cancel).unwrap();
    assert_eq!(awaiting_confirmation.status(), OfficeTaskStatus::Cancelled);

    let mut queued = single_output_task();
    queued.transition(TaskEvent::BeginPreflight).unwrap();
    queued.transition(TaskEvent::RequireConfirmation).unwrap();
    queued.transition(TaskEvent::ConfirmAndQueue).unwrap();
    queued.transition(TaskEvent::Cancel).unwrap();
    assert_eq!(queued.status(), OfficeTaskStatus::Cancelled);

    let mut running = single_output_task();
    advance_to_running(&mut running);
    running.transition(TaskEvent::Cancel).unwrap();
    assert_eq!(running.status(), OfficeTaskStatus::Cancelled);
}

#[test]
fn terminal_states_reject_all_further_transitions() {
    let mut task = single_output_task();
    advance_to_running(&mut task);
    task.complete_with_publication(publication_receipt(
        task_id(),
        OutputPolicy::SingleAtomic,
        OutputSummary::new(1, 1, 0),
    ))
    .unwrap();

    let error = task.transition(TaskEvent::Cancel).unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::InvalidStateTransition);
    assert_eq!(task.status(), OfficeTaskStatus::Succeeded);
}

#[test]
fn source_input_factory_only_accepts_read_only_access() {
    let input = OfficeInput::new(
        "input-safe-id",
        "C:/private/customer.xlsx",
        "xlsx",
        42,
        RequestedSourceAccess::ReadOnly,
    )
    .unwrap();
    assert_eq!(input.source_access(), SourceAccess::ReadOnly);

    let error = OfficeInput::new(
        "input-safe-id",
        "C:/private/customer.xlsx",
        "xlsx",
        42,
        RequestedSourceAccess::ReadWrite,
    )
    .unwrap_err();
    assert_eq!(error.code(), DomainErrorCode::SourceWriteForbidden);
    assert_eq!(error.to_string(), "OFFICE_SOURCE_WRITE_FORBIDDEN");
    assert!(!error.to_string().contains("customer.xlsx"));
}

#[test]
fn single_output_succeeds_only_when_the_one_output_is_published() {
    let mut task = single_output_task();
    advance_to_running(&mut task);

    task.complete_with_publication(publication_receipt(
        task_id(),
        OutputPolicy::SingleAtomic,
        OutputSummary::new(1, 1, 0),
    ))
    .unwrap();

    assert_eq!(task.status(), OfficeTaskStatus::Succeeded);
}

#[test]
fn single_output_failure_never_becomes_partial_success() {
    let mut task = single_output_task();
    advance_to_running(&mut task);

    task.complete_with_publication(publication_receipt(
        task_id(),
        OutputPolicy::SingleAtomic,
        OutputSummary::new(1, 0, 1),
    ))
    .unwrap();

    assert_eq!(task.status(), OfficeTaskStatus::Failed);
}

#[test]
fn single_output_derivation_rejects_published_and_failed_results() {
    let error = derive_completion_status(OutputPolicy::SingleAtomic, OutputSummary::new(1, 1, 1))
        .unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::OutputSummaryInconsistent);
}

#[test]
fn single_output_derivation_rejects_more_than_one_expected_output() {
    let error = derive_completion_status(OutputPolicy::SingleAtomic, OutputSummary::new(2, 2, 0))
        .unwrap_err();

    assert_eq!(
        error.code(),
        DomainErrorCode::SingleOutputCardinalityInvalid
    );
}

#[test]
fn multi_output_derives_success_partial_success_and_failure() {
    for (summary, expected_status) in [
        (OutputSummary::new(3, 3, 0), OfficeTaskStatus::Succeeded),
        (
            OutputSummary::new(3, 2, 1),
            OfficeTaskStatus::PartialSuccess,
        ),
        (OutputSummary::new(3, 0, 3), OfficeTaskStatus::Failed),
    ] {
        let mut task =
            OfficeTaskStateMachine::new(task_id(), None, OutputPolicy::MultipleIndependent);
        advance_to_running(&mut task);
        task.complete_with_publication(publication_receipt(
            task_id(),
            OutputPolicy::MultipleIndependent,
            summary,
        ))
        .unwrap();
        assert_eq!(task.status(), expected_status);
    }
}

#[test]
fn completion_rejects_zero_expected_outputs_and_non_conserving_counts() {
    for (summary, expected_error) in [
        (
            OutputSummary::new(0, 0, 0),
            DomainErrorCode::OutputExpectedInvalid,
        ),
        (
            OutputSummary::new(3, 1, 1),
            DomainErrorCode::OutputSummaryInconsistent,
        ),
        (
            OutputSummary::new(JS_SAFE_INTEGER_MAX + 1, JS_SAFE_INTEGER_MAX + 1, 0),
            DomainErrorCode::JsSafeIntegerExceeded,
        ),
    ] {
        let error =
            derive_completion_status(OutputPolicy::MultipleIndependent, summary).unwrap_err();
        assert_eq!(error.code(), expected_error);
    }
}

#[test]
fn completion_is_only_allowed_once() {
    let mut task = single_output_task();
    advance_to_running(&mut task);
    task.complete_with_publication(publication_receipt(
        task_id(),
        OutputPolicy::SingleAtomic,
        OutputSummary::new(1, 1, 0),
    ))
    .unwrap();

    let error = task
        .complete_with_publication(publication_receipt(
            task_id(),
            OutputPolicy::SingleAtomic,
            OutputSummary::new(1, 1, 0),
        ))
        .unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::InvalidStateTransition);
}

#[test]
fn completion_rejects_a_publication_receipt_for_another_task() {
    let mut task = single_output_task();
    advance_to_running(&mut task);

    let error = task
        .complete_with_publication(publication_receipt(
            other_task_id(),
            OutputPolicy::SingleAtomic,
            OutputSummary::new(1, 1, 0),
        ))
        .unwrap_err();

    assert_eq!(
        error.code(),
        DomainErrorCode::PublicationReceiptTaskMismatch
    );
    assert_eq!(task.status(), OfficeTaskStatus::Running);
}

#[test]
fn boundary_types_serialize_with_camel_case_names() {
    let task = OfficeTask::draft(
        task_id(),
        Some(request_id()),
        OfficeTaskType::ExcelMerge,
        OutputPolicy::MultipleIndependent,
        vec![],
    );
    let output = OfficeOutput {
        output_id: "output-safe-id".into(),
        input_id: None,
        status: OfficeOutputStatus::Planned,
    };
    let issue = OfficeIssue {
        issue_id: "issue-safe-id".into(),
        scope: OfficeIssueScope::Task,
        severity: OfficeIssueSeverity::Warning,
        code: "OFFICE_TEST_WARNING".into(),
        message_key: "office.issue.testWarning".into(),
        details_json: serde_json::json!({ "fieldCount": 2 })
            .as_object()
            .unwrap()
            .clone(),
        resolved: false,
    };

    let json = serde_json::to_value(task).unwrap();
    let output_json = serde_json::to_value(output).unwrap();
    let issue_json = serde_json::to_value(issue).unwrap();

    assert_eq!(json["taskId"], "00000000-0000-4000-8000-000000000001");
    assert_eq!(json["requestId"], "00000000-0000-4000-8000-000000000002");
    assert_eq!(json["taskType"], "excelMerge");
    assert_eq!(json["status"], "draft");
    assert_eq!(json["outputPolicy"], "multipleIndependent");
    assert_eq!(output_json["status"], "planned");
    assert_eq!(issue_json["messageKey"], "office.issue.testWarning");
    assert_eq!(issue_json["detailsJson"]["fieldCount"], 2);
}

#[test]
fn absent_optional_boundary_fields_are_omitted_instead_of_serialized_as_null() {
    let task = OfficeTask::draft(
        task_id(),
        None,
        OfficeTaskType::ExcelSplit,
        OutputPolicy::MultipleIndependent,
        vec![],
    );
    let output = OfficeOutput {
        output_id: "output-safe-id".into(),
        input_id: None,
        status: OfficeOutputStatus::Planned,
    };

    let json = serde_json::to_value(task).unwrap();
    let output_json = serde_json::to_value(output).unwrap();

    assert!(json.get("requestId").is_none());
    assert!(json.get("engine").is_none());
    assert!(output_json.get("inputId").is_none());
}
