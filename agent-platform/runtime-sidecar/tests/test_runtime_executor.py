from app.checkpoint_store import FileCheckpointStore
from app.callback_client import RuntimeNodeCallbackResponse
from app.models import ExecutionRequest, RuntimeEdge, RuntimeNode, WorkflowDefinition
from app.runtime_executor import build_events


def request(execution_id: str, runtime: dict):
    return ExecutionRequest(
        executionId=execution_id,
        rootExecutionId=execution_id,
        workflow=WorkflowDefinition(
            workflowId=42,
            name="checkpoint workflow",
            nodes=[
                RuntimeNode(id="start-1", type="START", label="Start", config={}),
                RuntimeNode(id="end-1", type="END", label="End", config={}),
            ],
            edges=[RuntimeEdge(source="start-1", target="end-1")],
        ),
        input={"message": "hello"},
        runtime=runtime,
    )


def test_build_events_restores_checkpoint_from_file_store_after_new_instance(tmp_path):
    first_store = FileCheckpointStore(tmp_path)
    build_events(request("1001", {"checkpoint": True}), first_store)

    second_store = FileCheckpointStore(tmp_path)
    events = build_events(
        request("1002", {"checkpoint": True, "resumeFromCheckpointRef": "checkpoint-1001"}),
        second_store,
    )

    assert all(event.metadata["checkpointRestored"] is True for event in events)
    assert all(event.metadata["restoredCheckpointRef"] == "checkpoint-1001" for event in events)


def test_build_events_resume_emits_only_nodes_executed_after_checkpoint(tmp_path):
    store = FileCheckpointStore(tmp_path)
    store.save(
        "checkpoint-1001",
        {
            "input": {"message": "hello"},
            "visited": ["start-1"],
            "outputs": {"start-1": {"nodeId": "start-1"}},
        },
    )

    events = build_events(
        request("1002", {"checkpoint": True, "resumeFromCheckpointRef": "checkpoint-1001"}),
        store,
    )

    completed_node_ids = [event.nodeId for event in events if event.type == "NODE_COMPLETED"]
    assert completed_node_ids == ["end-1"]


def test_build_events_accepts_checkpoint_store_protocol():
    class RecordingCheckpointStore:
        def __init__(self):
            self.saved = {}

        def save(self, checkpoint_ref, state):
            self.saved[checkpoint_ref] = state

        def load(self, checkpoint_ref):
            return None

    store = RecordingCheckpointStore()

    build_events(request("1001", {"checkpoint": True}), store)

    assert "checkpoint-1001" in store.saved


def test_build_events_uses_java_callback_output_for_skill_nodes():
    callback_requests = []

    def callback(java_base_url, callback_request):
        callback_requests.append((java_base_url, callback_request))
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[12],
            stepOutputs=[{"skillId": 12, "output": "skill output"}],
            output={"text": "skill output"},
            metadata={"traceId": "trace-1001"},
        )

    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=WorkflowDefinition(
                workflowId=42,
                name="skill workflow",
                nodes=[
                    RuntimeNode(id="start-1", type="START", label="Start", config={}),
                    RuntimeNode(id="skill-1", type="SKILL", label="Skill", config={"skillId": 12}),
                    RuntimeNode(id="end-1", type="END", label="End", config={}),
                ],
                edges=[
                    RuntimeEdge(source="start-1", target="skill-1"),
                    RuntimeEdge(source="skill-1", target="end-1"),
                ],
            ),
            input={"message": "hello"},
            runtime={"javaCallbackBaseUrl": "http://java:8080", "traceId": "trace-1001"},
        ),
        callback_executor=callback,
    )

    completed = [event for event in events if event.type == "NODE_COMPLETED" and event.nodeId == "skill-1"]
    started = [event for event in events if event.type == "NODE_STARTED" and event.nodeId == "skill-1"]
    assert started[0].input == {"message": "hello"}
    assert completed[0].output["text"] == "skill output"
    assert completed[0].output["selectedSkillIds"] == [12]
    assert completed[0].output["stepOutputs"] == [{"skillId": 12, "output": "skill output"}]
    assert callback_requests[0][0] == "http://java:8080"
    assert callback_requests[0][1].sourceType == "SKILL"
    assert callback_requests[0][1].sourceId == 12
    assert callback_requests[0][1].input == {"message": "hello"}


def test_build_events_passes_upstream_output_keys_to_downstream_callback():
    callback_requests = []

    def callback(java_base_url, callback_request):
        callback_requests.append(callback_request)
        if callback_request.nodeId == "skill-a":
            return RuntimeNodeCallbackResponse(
                success=True,
                selectedSkillIds=[12],
                stepOutputs=[{"skillId": 12, "output": "summary value"}],
                output={"text": "summary value", "outputKey": "summary"},
                metadata={},
            )
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[13],
            stepOutputs=[{"skillId": 13, "output": "final"}],
            output={"text": "final"},
            metadata={},
        )

    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=WorkflowDefinition(
                workflowId=42,
                name="chained workflow",
                nodes=[
                    RuntimeNode(id="skill-a", type="SKILL", label="A", config={"skillId": 12}),
                    RuntimeNode(id="skill-b", type="SKILL", label="B", config={"skillId": 13}),
                ],
                edges=[RuntimeEdge(source="skill-a", target="skill-b")],
            ),
            input={"message": "hello"},
            runtime={"javaCallbackBaseUrl": "http://java:8080"},
        ),
        callback_executor=callback,
    )

    assert callback_requests[1].input["summary"] == "summary value"
    started_b = [event for event in events if event.type == "NODE_STARTED" and event.nodeId == "skill-b"]
    assert started_b[0].input["summary"] == "summary value"


def test_build_events_passes_node_alias_scoped_outputs_to_downstream_callback():
    callback_requests = []

    def callback(java_base_url, callback_request):
        callback_requests.append(callback_request)
        if callback_request.nodeId == "skill-a":
            return RuntimeNodeCallbackResponse(
                success=True,
                selectedSkillIds=[12],
                stepOutputs=[],
                output={"text": "summary value", "outputKey": "summary"},
                metadata={},
            )
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[13],
            stepOutputs=[],
            output={"text": "final"},
            metadata={},
        )

    build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=WorkflowDefinition(
                workflowId=42,
                name="scoped workflow",
                nodes=[
                    RuntimeNode(id="skill-a", type="SKILL", label="A", config={"skillId": 12, "nodeAlias": "summaryA"}),
                    RuntimeNode(id="skill-b", type="SKILL", label="B", config={"skillId": 13, "nodeAlias": "summaryB"}),
                ],
                edges=[RuntimeEdge(source="skill-a", target="skill-b")],
            ),
            input={"message": "hello"},
            runtime={"javaCallbackBaseUrl": "http://java:8080"},
        ),
        callback_executor=callback,
    )

    assert callback_requests[1].input["summaryA.summary"] == "summary value"


def test_build_events_outputs_input_node_value():
    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            workflow=WorkflowDefinition(
                workflowId=42,
                name="input workflow",
                nodes=[
                    RuntimeNode(
                        id="input-prompt",
                        type="INPUT",
                        label="Prompt",
                        config={"inputKey": "prompt", "inputType": "textarea"},
                    )
                ],
                edges=[],
            ),
            input={"prompt": "write a deployment summary"},
            runtime={"traceId": "trace-1001"},
        )
    )

    completed = [event for event in events if event.type == "NODE_COMPLETED" and event.nodeId == "input-prompt"]
    assert completed[0].output["value"] == "write a deployment summary"
    assert completed[0].output["inputKey"] == "prompt"
    assert completed[0].output["inputType"] == "textarea"


def test_build_events_passes_start_node_input_as_scoped_value_to_downstream_callback():
    callback_requests = []

    def callback(java_base_url, callback_request):
        callback_requests.append(callback_request)
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[12],
            stepOutputs=[],
            output={"text": "done"},
            metadata={},
        )

    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=WorkflowDefinition(
                workflowId=42,
                name="start input workflow",
                nodes=[
                    RuntimeNode(
                        id="start-1",
                        type="START",
                        label="Start",
                        config={"inputKey": "ccc", "nodeAlias": "node_start_1"},
                    ),
                    RuntimeNode(id="skill-1", type="SKILL", label="Skill", config={"skillId": 12}),
                ],
                edges=[RuntimeEdge(source="start-1", target="skill-1")],
            ),
            input={"ccc": "start value"},
            runtime={"javaCallbackBaseUrl": "http://java:8080"},
        ),
        callback_executor=callback,
    )

    start_completed = [event for event in events if event.type == "NODE_COMPLETED" and event.nodeId == "start-1"]
    assert start_completed[0].output["value"] == "start value"
    assert start_completed[0].output["inputKey"] == "ccc"
    assert callback_requests[0].input["ccc"] == "start value"
    assert callback_requests[0].input["node_start_1.ccc"] == "start value"


def test_build_events_uses_java_callback_output_for_agent_ref_nodes():
    callback_requests = []

    def callback(java_base_url, callback_request):
        callback_requests.append((java_base_url, callback_request))
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[12, 13],
            stepOutputs=[
                {"skillId": 12, "output": "outline"},
                {"skillId": 13, "output": "final docs"},
            ],
            output={"text": "final docs", "agentId": 3, "agentName": "writer"},
            metadata={"traceId": "trace-1001"},
        )

    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=WorkflowDefinition(
                workflowId=42,
                name="agent workflow",
                nodes=[
                    RuntimeNode(id="agent-1", type="AGENT_REF", label="Agent", config={"agentId": 3}),
                ],
                edges=[],
            ),
            input={"message": "write docs"},
            runtime={"javaCallbackBaseUrl": "http://java:8080", "traceId": "trace-1001"},
        ),
        callback_executor=callback,
    )

    completed = [event for event in events if event.type == "NODE_COMPLETED" and event.nodeId == "agent-1"]
    assert completed[0].output["text"] == "final docs"
    assert completed[0].output["agentId"] == 3
    assert completed[0].output["selectedSkillIds"] == [12, 13]
    assert completed[0].output["stepOutputs"][1]["output"] == "final docs"
    assert callback_requests[0][1].sourceType == "AGENT"
    assert callback_requests[0][1].sourceId == 3


def test_build_events_converts_java_callback_failure_to_execution_failed():
    def callback(java_base_url, callback_request):
        raise RuntimeError("java callback 500")

    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=WorkflowDefinition(
                workflowId=42,
                name="callback failure workflow",
                nodes=[
                    RuntimeNode(id="skill-1", type="SKILL", label="Skill", config={"skillId": 12}),
                ],
                edges=[],
            ),
            input={"message": "hello"},
            runtime={
                "javaCallbackBaseUrl": "http://java:8080",
                "traceId": "trace-1001",
                "checkpoint": True,
            },
        ),
        callback_executor=callback,
    )

    assert events[-1].type == "EXECUTION_FAILED"
    assert events[-1].status == "FAILED"
    assert events[-1].nodeId == "skill-1"
    assert events[-1].metadata["failedNodeId"] == "skill-1"
    assert events[-1].metadata["errorMessage"] == "java callback 500"
    assert events[-1].metadata["recoveryCheckpointRef"] == "checkpoint-1001"
