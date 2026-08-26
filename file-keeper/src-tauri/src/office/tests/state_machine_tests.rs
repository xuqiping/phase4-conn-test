use super::state_machine::{OfficeTaskStateMachine, TaskEvent};
use super::types::{
    DomainErrorCode, OfficeEngine, OfficeInput, OfficeIssue, OfficeOutput, OfficeTask,
    OfficeTaskStatus, OfficeTaskType, OutputPolicy, OutputSummary, RequestedSourceAccess,
    SourceAccess,
};

fn single_output_task() -> OfficeTaskStateMachine {
    OfficeTaskStateMachine::new(
        "task-safe-id",
        Some("request-safe-id".into()),
        OutputPolicy::SingleAtomic,
    )
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

    assert_eq!(task.task_id(), "task-safe-id");
    assert_eq!(task.request_id(), Some("request-safe-id"));
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
    let error = task.complete(OutputSummary::new(1, 1, 0)).unwrap_err();
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
    task.complete(OutputSummary::new(1, 1, 0)).unwrap();

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
    assert_eq!(input.source_access, SourceAccess::ReadOnly);

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

    task.complete(OutputSummary::new(1, 1, 0)).unwrap();

    assert_eq!(task.status(), OfficeTaskStatus::Succeeded);
}

#[test]
fn single_output_failure_never_becomes_partial_success() {
    let mut task = single_output_task();
    advance_to_running(&mut task);

    task.complete(OutputSummary::new(1, 0, 1)).unwrap();

    assert_eq!(task.status(), OfficeTaskStatus::Failed);
}

#[test]
fn single_output_rejects_a_summary_that_claims_published_and_failed_results() {
    let mut task = single_output_task();
    advance_to_running(&mut task);

    let error = task.complete(OutputSummary::new(1, 1, 1)).unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::OutputSummaryInconsistent);
    assert_eq!(task.status(), OfficeTaskStatus::Running);
}

#[test]
fn single_output_rejects_more_than_one_expected_output() {
    let mut task = single_output_task();
    advance_to_running(&mut task);

    let error = task.complete(OutputSummary::new(2, 2, 0)).unwrap_err();

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
            OfficeTaskStateMachine::new("task-safe-id", None, OutputPolicy::MultipleIndependent);
        advance_to_running(&mut task);
        task.complete(summary).unwrap();
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
            OutputSummary::new(u64::MAX, u64::MAX, 1),
            DomainErrorCode::OutputCountOverflow,
        ),
    ] {
        let mut task =
            OfficeTaskStateMachine::new("task-safe-id", None, OutputPolicy::MultipleIndependent);
        advance_to_running(&mut task);
        let error = task.complete(summary).unwrap_err();
        assert_eq!(error.code(), expected_error);
        assert_eq!(task.status(), OfficeTaskStatus::Running);
    }
}

#[test]
fn completion_is_only_allowed_once() {
    let mut task = single_output_task();
    advance_to_running(&mut task);
    task.complete(OutputSummary::new(1, 1, 0)).unwrap();

    let error = task.complete(OutputSummary::new(1, 1, 0)).unwrap_err();

    assert_eq!(error.code(), DomainErrorCode::InvalidStateTransition);
}

#[test]
fn boundary_types_serialize_with_camel_case_names() {
    let task = OfficeTask {
        task_id: "task-safe-id".into(),
        request_id: Some("request-safe-id".into()),
        task_type: OfficeTaskType::ExcelMerge,
        status: OfficeTaskStatus::AwaitingConfirmation,
        engine: Some(OfficeEngine::OoxmlWorker),
        output_policy: OutputPolicy::MultipleIndependent,
        inputs: vec![],
        outputs: vec![OfficeOutput {
            output_id: "output-safe-id".into(),
            input_id: None,
            status: "planned".into(),
        }],
        issues: vec![OfficeIssue {
            issue_id: "issue-safe-id".into(),
            scope: "task".into(),
            severity: "warning".into(),
            code: "OFFICE_TEST_WARNING".into(),
            message_key: "office.issue.testWarning".into(),
            details_json: serde_json::json!({ "fieldCount": 2 })
                .as_object()
                .unwrap()
                .clone(),
            resolved: false,
        }],
    };

    let json = serde_json::to_value(task).unwrap();

    assert_eq!(json["taskId"], "task-safe-id");
    assert_eq!(json["requestId"], "request-safe-id");
    assert_eq!(json["taskType"], "excelMerge");
    assert_eq!(json["status"], "awaitingConfirmation");
    assert_eq!(json["engine"], "ooxmlWorker");
    assert_eq!(json["outputPolicy"], "multipleIndependent");
    assert_eq!(json["issues"][0]["messageKey"], "office.issue.testWarning");
    assert_eq!(json["issues"][0]["detailsJson"]["fieldCount"], 2);
}

#[test]
fn absent_optional_boundary_fields_are_omitted_instead_of_serialized_as_null() {
    let task = OfficeTask {
        task_id: "task-safe-id".into(),
        request_id: None,
        task_type: OfficeTaskType::ExcelSplit,
        status: OfficeTaskStatus::Draft,
        engine: None,
        output_policy: OutputPolicy::MultipleIndependent,
        inputs: vec![],
        outputs: vec![OfficeOutput {
            output_id: "output-safe-id".into(),
            input_id: None,
            status: "planned".into(),
        }],
        issues: vec![],
    };

    let json = serde_json::to_value(task).unwrap();

    assert!(json.get("requestId").is_none());
    assert!(json.get("engine").is_none());
    assert!(json["outputs"][0].get("inputId").is_none());
}
