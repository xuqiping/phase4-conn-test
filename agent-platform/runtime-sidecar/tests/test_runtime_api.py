import json
import asyncio
import time

from fastapi.testclient import TestClient

from app.main import app
from app.main import stream_events
from app.models import ExecutionEvent


client = TestClient(app)


def sample_request(stream=True):
    return {
        "executionId": "1001",
        "rootExecutionId": "1001",
        "parentExecutionId": None,
        "userId": 7,
        "sourceType": "WORKFLOW",
        "sourceId": 42,
        "workflow": {
            "version": "2026-06-03",
            "workflowId": 42,
            "name": "sidecar smoke workflow",
            "nodes": [
                {"id": "start-1", "type": "START", "label": "Start", "config": {}},
                {
                    "id": "agent-1",
                    "type": "AGENT_REF",
                    "label": "Writer Agent",
                    "config": {"agentId": 3, "agentName": "Writer"},
                },
                {"id": "end-1", "type": "END", "label": "End", "config": {}},
            ],
            "edges": [
                {"source": "start-1", "target": "agent-1"},
                {"source": "agent-1", "target": "end-1"},
            ],
        },
        "input": {"message": "hello"},
        "runtime": {"stream": stream, "traceId": "trace-1001"},
    }


def test_health_reports_runtime_sidecar():
    response = client.get("/health")

    assert response.status_code == 200
    assert response.json() == {"status": "UP", "service": "runtime-sidecar"}


def test_execute_returns_ordered_events_for_non_stream_request():
    response = client.post("/api/runtime/executions", json=sample_request(stream=False))

    assert response.status_code == 200
    body = response.json()
    assert body["executionId"] == "1001"
    assert body["status"] == "SUCCESS"
    assert [event["type"] for event in body["events"]] == [
        "EXECUTION_STARTED",
        "NODE_STARTED",
        "NODE_COMPLETED",
        "NODE_STARTED",
        "NODE_COMPLETED",
        "NODE_STARTED",
        "NODE_COMPLETED",
        "EXECUTION_COMPLETED",
    ]
    assert body["events"][0]["metadata"]["traceId"] == "trace-1001"
    assert body["events"][0]["metadata"]["externalThreadId"] == "sidecar-thread-1001"
    assert body["events"][0]["metadata"]["engine"] == "langgraph"
    assert body["events"][2]["nodeId"] == "start-1"
    assert body["events"][4]["sourceType"] == "AGENT"
    assert body["events"][4]["sourceId"] == 3


def test_execute_emits_checkpoint_ref_when_checkpoint_enabled():
    request = sample_request(stream=False)
    request["runtime"]["checkpoint"] = True

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    assert all(event["metadata"]["checkpointRef"] == "checkpoint-1001" for event in events)


def test_execute_includes_resume_checkpoint_ref_when_resuming():
    request = sample_request(stream=False)
    request["runtime"]["checkpoint"] = True
    request["runtime"]["resumeFromCheckpointRef"] = "checkpoint-99"

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    assert all(event["metadata"]["resumeFromCheckpointRef"] == "checkpoint-99" for event in events)


def test_execute_restores_state_from_checkpoint_ref_when_available():
    first_request = sample_request(stream=False)
    first_request["runtime"]["checkpoint"] = True
    first_response = client.post("/api/runtime/executions", json=first_request)
    assert first_response.status_code == 200

    resume_request = sample_request(stream=False)
    resume_request["executionId"] = "1002"
    resume_request["rootExecutionId"] = "1002"
    resume_request["runtime"]["checkpoint"] = True
    resume_request["runtime"]["resumeFromCheckpointRef"] = "checkpoint-1001"

    response = client.post("/api/runtime/executions", json=resume_request)

    assert response.status_code == 200
    events = response.json()["events"]
    assert all(event["metadata"]["checkpointRestored"] is True for event in events)
    assert all(event["metadata"]["restoredCheckpointRef"] == "checkpoint-1001" for event in events)


def test_execute_streams_sse_events_when_stream_enabled():
    with client.stream("POST", "/api/runtime/executions", json=sample_request(stream=True)) as response:
        assert response.status_code == 200
        assert response.headers["content-type"].startswith("text/event-stream")
        lines = [line for line in response.iter_lines() if line.startswith("data: ")]

    events = [json.loads(line.removeprefix("data: ")) for line in lines]
    assert events[0]["type"] == "EXECUTION_STARTED"
    assert events[-1]["type"] == "EXECUTION_COMPLETED"
    assert all(event["executionId"] == "1001" for event in events)


def test_stream_events_does_not_block_event_loop_between_events():
    class BlockingEvents:
        def __init__(self):
            self.index = 0

        def __iter__(self):
            return self

        def __next__(self):
            self.index += 1
            if self.index == 1:
                return ExecutionEvent(executionId="1001", rootExecutionId="1001", type="EXECUTION_STARTED", status="RUNNING")
            if self.index == 2:
                time.sleep(0.15)
                return ExecutionEvent(executionId="1001", rootExecutionId="1001", type="NODE_STARTED", status="RUNNING")
            raise StopIteration

    async def run_probe():
        events = stream_events(BlockingEvents())
        first = await events.__anext__()
        assert first["event"] == "EXECUTION_STARTED"

        second_task = asyncio.create_task(events.__anext__())
        start = time.perf_counter()
        await asyncio.sleep(0.02)
        elapsed = time.perf_counter() - start
        second = await second_task

        assert elapsed < 0.1
        assert second["event"] == "NODE_STARTED"

    asyncio.run(run_probe())


def test_rejects_workflow_without_nodes():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = []

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 422
    assert "workflow.nodes must not be empty" in response.text


def test_execute_condition_node_returns_only_selected_branch_events():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {"id": "condition-1", "type": "CONDITION", "label": "Route", "config": {"inputPath": "route"}},
        {"id": "agent-a", "type": "AGENT_REF", "label": "Agent A", "config": {"agentId": 1}},
        {"id": "agent-b", "type": "AGENT_REF", "label": "Agent B", "config": {"agentId": 2}},
        {"id": "end-1", "type": "END", "label": "End", "config": {}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "condition-1"},
        {"source": "condition-1", "target": "agent-a", "condition": "a"},
        {"source": "condition-1", "target": "agent-b", "condition": "b"},
        {"source": "agent-a", "target": "end-1"},
        {"source": "agent-b", "target": "end-1"},
    ]
    request["input"] = {"route": "b"}

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    completed_node_ids = [event["nodeId"] for event in events if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids == ["start-1", "condition-1", "agent-b", "end-1"]
    assert "agent-a" not in completed_node_ids
    condition_event = next(event for event in events if event["nodeId"] == "condition-1" and event["type"] == "NODE_COMPLETED")
    assert condition_event["output"]["selectedTarget"] == "agent-b"


def test_execute_condition_node_supports_comparison_expression_events():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {"id": "condition-1", "type": "CONDITION", "label": "Score Route", "config": {}},
        {"id": "high-score", "type": "AGENT_REF", "label": "High Score", "config": {"agentId": 1}},
        {"id": "low-score", "type": "AGENT_REF", "label": "Low Score", "config": {"agentId": 2}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "condition-1"},
        {"source": "condition-1", "target": "high-score", "condition": "score >= 80"},
        {"source": "condition-1", "target": "low-score", "condition": "score < 80"},
    ]
    request["input"] = {"score": 91}

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    completed_node_ids = [event["nodeId"] for event in events if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids == ["start-1", "condition-1", "high-score"]
    assert "low-score" not in completed_node_ids


def test_execute_condition_node_supports_structured_all_any_groups():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {
            "id": "condition-1",
            "type": "CONDITION",
            "label": "Structured Route",
            "config": {
                "conditions": [
                    {
                        "name": "vip-high",
                        "target": "priority-agent",
                        "all": [
                            {"field": "score", "operator": ">=", "value": 80},
                            {
                                "any": [
                                    {"field": "tags", "operator": "contains", "value": "vip"},
                                    {"field": "tier", "operator": "==", "value": "gold"},
                                ]
                            },
                        ],
                    },
                    {
                        "name": "normal",
                        "target": "normal-agent",
                        "all": [{"field": "score", "operator": "<", "value": 80}],
                    },
                ]
            },
        },
        {"id": "priority-agent", "type": "AGENT_REF", "label": "Priority", "config": {"agentId": 1}},
        {"id": "normal-agent", "type": "AGENT_REF", "label": "Normal", "config": {"agentId": 2}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "condition-1"},
        {"source": "condition-1", "target": "priority-agent"},
        {"source": "condition-1", "target": "normal-agent"},
    ]
    request["input"] = {"score": 91, "tags": ["vip"], "tier": "basic"}

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    completed_node_ids = [event["nodeId"] for event in events if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids == ["start-1", "condition-1", "priority-agent"]
    condition_event = next(event for event in events if event["nodeId"] == "condition-1" and event["type"] == "NODE_COMPLETED")
    assert condition_event["output"]["selectedRoute"] == "vip-high"
    assert condition_event["output"]["selectedTarget"] == "priority-agent"


def test_execute_router_node_returns_only_selected_route_events():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {
            "id": "router-1",
            "type": "ROUTER",
            "label": "Intent Router",
            "config": {
                "routes": [
                    {"name": "sales", "condition": "intent == sales", "target": "sales-agent"},
                    {"name": "support", "condition": "intent == support", "target": "support-agent"},
                ],
            },
        },
        {"id": "sales-agent", "type": "AGENT_REF", "label": "Sales", "config": {"agentId": 1}},
        {"id": "support-agent", "type": "AGENT_REF", "label": "Support", "config": {"agentId": 2}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "router-1"},
        {"source": "router-1", "target": "sales-agent"},
        {"source": "router-1", "target": "support-agent"},
    ]
    request["input"] = {"intent": "support"}

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    completed_node_ids = [event["nodeId"] for event in events if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids == ["start-1", "router-1", "support-agent"]
    assert "sales-agent" not in completed_node_ids
    router_event = next(event for event in events if event["nodeId"] == "router-1" and event["type"] == "NODE_COMPLETED")
    assert router_event["output"]["selectedRoute"] == "support"
    assert router_event["output"]["selectedTarget"] == "support-agent"


def test_execute_llm_router_mock_selects_route_with_reason_and_confidence():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {
            "id": "llm-router-1",
            "type": "LLM_ROUTER",
            "label": "LLM Router",
            "config": {
                "mockSelectedRoute": "support",
                "routes": [
                    {"name": "sales", "target": "sales-agent"},
                    {"name": "support", "target": "support-agent"},
                ],
            },
        },
        {"id": "sales-agent", "type": "AGENT_REF", "label": "Sales", "config": {"agentId": 1}},
        {"id": "support-agent", "type": "AGENT_REF", "label": "Support", "config": {"agentId": 2}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "llm-router-1"},
        {"source": "llm-router-1", "target": "sales-agent"},
        {"source": "llm-router-1", "target": "support-agent"},
    ]

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    completed_node_ids = [event["nodeId"] for event in events if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids == ["start-1", "llm-router-1", "support-agent"]
    router_event = next(event for event in events if event["nodeId"] == "llm-router-1" and event["type"] == "NODE_COMPLETED")
    assert router_event["output"]["selectedRoute"] == "support"
    assert router_event["output"]["selectedTarget"] == "support-agent"
    assert router_event["output"]["reason"] == "mock LLM router selected support"
    assert router_event["output"]["confidence"] == 1.0


def test_execute_parallel_join_returns_joined_branch_outputs():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {"id": "parallel-1", "type": "PARALLEL", "label": "Parallel", "config": {}},
        {"id": "agent-a", "type": "AGENT_REF", "label": "Agent A", "config": {"agentId": 1}},
        {"id": "agent-b", "type": "AGENT_REF", "label": "Agent B", "config": {"agentId": 2}},
        {"id": "join-1", "type": "JOIN", "label": "Join", "config": {}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "parallel-1"},
        {"source": "parallel-1", "target": "agent-a"},
        {"source": "parallel-1", "target": "agent-b"},
        {"source": "agent-a", "target": "join-1"},
        {"source": "agent-b", "target": "join-1"},
    ]

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    events = response.json()["events"]
    completed_node_ids = [event["nodeId"] for event in events if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids[0:2] == ["start-1", "parallel-1"]
    assert set(completed_node_ids[2:4]) == {"agent-a", "agent-b"}
    assert completed_node_ids[4] == "join-1"
    join_event = next(event for event in events if event["nodeId"] == "join-1" and event["type"] == "NODE_COMPLETED")
    assert join_event["output"]["joinedNodeIds"] == ["agent-a", "agent-b"]
    assert set(join_event["output"]["joinedOutputs"].keys()) == {"agent-a", "agent-b"}


def test_execute_parallel_continue_policy_joins_successful_and_failed_branches():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {"id": "parallel-1", "type": "PARALLEL", "label": "Parallel", "config": {"branchFailurePolicy": "continue"}},
        {"id": "agent-a", "type": "AGENT_REF", "label": "Agent A", "config": {"agentId": 1}},
        {"id": "agent-b", "type": "AGENT_REF", "label": "Agent B", "config": {"agentId": 2, "fail": True}},
        {"id": "join-1", "type": "JOIN", "label": "Join", "config": {}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "parallel-1"},
        {"source": "parallel-1", "target": "agent-a"},
        {"source": "parallel-1", "target": "agent-b"},
        {"source": "agent-a", "target": "join-1"},
        {"source": "agent-b", "target": "join-1"},
    ]

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "SUCCESS"
    join_event = next(event for event in body["events"] if event["nodeId"] == "join-1" and event["type"] == "NODE_COMPLETED")
    assert join_event["output"]["joinedOutputs"]["agent-a"]["status"] == "SUCCESS"
    assert join_event["output"]["joinedOutputs"]["agent-b"]["status"] == "FAILED"
    assert join_event["output"]["joinedOutputs"]["agent-b"]["errorMessage"] == "node agent-b failed by config"


def test_execute_failed_node_returns_failure_event_with_recovery_checkpoint():
    request = sample_request(stream=False)
    request["runtime"]["checkpoint"] = True
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {"id": "agent-1", "type": "AGENT_REF", "label": "Broken Agent", "config": {"agentId": 1, "fail": True}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "agent-1"},
    ]

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "FAILED"
    assert body["events"][-1]["type"] == "EXECUTION_FAILED"
    assert body["events"][-1]["status"] == "FAILED"
    assert body["events"][-1]["metadata"]["failedNodeId"] == "agent-1"
    assert body["events"][-1]["metadata"]["recoveryCheckpointRef"] == "checkpoint-1001"


def test_execute_human_approval_node_waits_with_checkpoint():
    request = sample_request(stream=False)
    request["runtime"]["checkpoint"] = True
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {
            "id": "approval-1",
            "type": "HUMAN_APPROVAL",
            "label": "Manager Approval",
            "config": {"approvalKey": "deploy-prod"},
        },
        {"id": "end-1", "type": "END", "label": "End", "config": {}},
    ]
    request["workflow"]["edges"] = [
        {"source": "start-1", "target": "approval-1"},
        {"source": "approval-1", "target": "end-1"},
    ]

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    body = response.json()
    assert body["status"] == "WAITING_APPROVAL"
    assert body["events"][-1]["type"] == "WAITING_APPROVAL"
    assert body["events"][-1]["nodeId"] == "approval-1"
    assert body["events"][-1]["metadata"]["approvalKey"] == "deploy-prod"
    assert body["events"][-1]["metadata"]["approvalCheckpointRef"] == "checkpoint-1001"
    completed_node_ids = [event["nodeId"] for event in body["events"] if event["type"] == "NODE_COMPLETED"]
    assert completed_node_ids == ["start-1"]
