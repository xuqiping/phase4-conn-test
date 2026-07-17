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


def _human_input_workflow() -> WorkflowDefinition:
    return WorkflowDefinition(
        workflowId=42,
        name="human input workflow",
        nodes=[
            RuntimeNode(id="start-1", type="START", label="Start", config={}),
            RuntimeNode(
                id="human-1",
                type="HUMAN_INPUT",
                label="Ask Budget",
                config={
                    "inputKey": "budget",
                    "questionTemplate": "请告诉我你的预算范围",
                    "inputType": "text",
                    "nodeAlias": "askBudget",
                },
            ),
            RuntimeNode(id="skill-1", type="SKILL", label="Skill", config={"skillId": 12}),
        ],
        edges=[
            RuntimeEdge(source="start-1", target="human-1"),
            RuntimeEdge(source="human-1", target="skill-1"),
        ],
    )


def test_build_events_pauses_at_human_input_node_and_emits_waiting_input(tmp_path):
    store = FileCheckpointStore(tmp_path)

    def callback(java_base_url, callback_request):
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[12],
            stepOutputs=[],
            output={"text": "ignored on first pass"},
            metadata={},
        )

    events = build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=_human_input_workflow(),
            input={"message": "hello"},
            runtime={"javaCallbackBaseUrl": "http://java:8080", "checkpoint": True},
        ),
        checkpoint_store=store,
        callback_executor=callback,
    )

    completed_node_ids = [event.nodeId for event in events if event.type == "NODE_COMPLETED"]
    assert "human-1" not in completed_node_ids
    assert "skill-1" not in completed_node_ids
    waiting = [event for event in events if event.type == "WAITING_INPUT"]
    assert len(waiting) == 1
    assert waiting[0].nodeId == "human-1"
    assert waiting[0].status == "WAITING_INPUT"
    assert waiting[0].metadata["inputKey"] == "budget"
    assert waiting[0].metadata["question"] == "请告诉我你的预算范围"
    assert waiting[0].metadata["inputType"] == "text"
    assert waiting[0].metadata["inputCheckpointRef"] == "checkpoint-1001"

    saved = store.load("checkpoint-1001")
    assert saved["pausedAtNodeId"] == "human-1"


def test_build_events_resumes_human_input_with_user_input_and_emits_downstream(tmp_path):
    store = FileCheckpointStore(tmp_path)

    # first run primes the paused checkpoint
    def first_callback(java_base_url, callback_request):
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[12],
            stepOutputs=[],
            output={"text": "first pass"},
            metadata={},
        )

    build_events(
        ExecutionRequest(
            executionId="1001",
            rootExecutionId="1001",
            userId=7,
            workflow=_human_input_workflow(),
            input={"message": "hello"},
            runtime={"javaCallbackBaseUrl": "http://java:8080", "checkpoint": True},
        ),
        checkpoint_store=store,
        callback_executor=first_callback,
    )

    callback_requests = []

    def resume_callback(java_base_url, callback_request):
        callback_requests.append(callback_request)
        return RuntimeNodeCallbackResponse(
            success=True,
            selectedSkillIds=[12],
            stepOutputs=[],
            output={"text": f"budget={callback_request.input.get('budget')}"},
            metadata={},
        )

    events = build_events(
        ExecutionRequest(
            executionId="1002",
            rootExecutionId="1002",
            userId=7,
            workflow=_human_input_workflow(),
            input={"message": "hello"},
            runtime={
                "javaCallbackBaseUrl": "http://java:8080",
                "checkpoint": True,
                "resumeFromCheckpointRef": "checkpoint-1001",
                "userInput": {"budget": "5000"},
            },
        ),
        checkpoint_store=store,
        callback_executor=resume_callback,
    )

    completed_node_ids = [event.nodeId for event in events if event.type == "NODE_COMPLETED"]
    assert completed_node_ids == ["human-1", "skill-1"]

    human_completed = [event for event in events if event.type == "NODE_COMPLETED" and event.nodeId == "human-1"][0]
    assert human_completed.output["value"] == "5000"

    assert callback_requests[0].input["budget"] == "5000"
    assert callback_requests[0].input["askBudget.budget"] == "5000"

    skill_completed = [event for event in events if event.type == "NODE_COMPLETED" and event.nodeId == "skill-1"][0]
    assert skill_completed.output["text"] == "budget=5000"

    assert events[-1].type == "EXECUTION_COMPLETED"


def _cyclic_workflow() -> WorkflowDefinition:
    """Phase 2 环支持：A↔B 互指成环（无 HUMAN_INPUT 的纯环，用于验证环可编译 + 递归守卫）。"""
    return WorkflowDefinition(
        workflowId=42,
        name="cyclic workflow",
        nodes=[
            RuntimeNode(id="loop-a", type="TASK", label="A", config={}),
            RuntimeNode(id="loop-b", type="TASK", label="B", config={}),
        ],
        edges=[
            RuntimeEdge(source="loop-a", target="loop-b"),
            RuntimeEdge(source="loop-b", target="loop-a"),
        ],
    )


def test_build_events_cyclic_workflow_hits_recursion_guard(tmp_path):
    store = FileCheckpointStore(tmp_path)

    events = build_events(
        ExecutionRequest(
            executionId="2001",
            rootExecutionId="2001",
            userId=7,
            workflow=_cyclic_workflow(),
            input={"message": "hello"},
            runtime={"checkpoint": True, "recursionLimit": 6},
        ),
        checkpoint_store=store,
    )

    # 纯环无停止条件 → 触 recursion_limit → GraphRecursionError 捕获 → EXECUTION_FAILED（清晰报错，非裸崩）
    assert events[-1].type == "EXECUTION_FAILED"
    assert events[-1].status == "FAILED"
    assert "最大迭代" in events[-1].metadata["errorMessage"]
    assert "6" in events[-1].metadata["errorMessage"]
    assert events[-1].metadata["recoveryCheckpointRef"] == "checkpoint-2001"


def test_build_events_recursion_limit_default_when_unspecified():
    # 不显式指定时回退默认 25（与 LangGraph 默认一致），非法值也回退默认
    from app.runtime_executor import resolve_recursion_limit

    assert resolve_recursion_limit({}) == 25
    assert resolve_recursion_limit({"recursionLimit": None}) == 25
    assert resolve_recursion_limit({"recursionLimit": 50}) == 50
    assert resolve_recursion_limit({"recursionLimit": "40"}) == 40
    assert resolve_recursion_limit({"recursionLimit": "garbage"}) == 25
    assert resolve_recursion_limit({"recursionLimit": 0}) == 25
    assert resolve_recursion_limit({"recursionLimit": -3}) == 25


def _cyclic_human_input_workflow() -> WorkflowDefinition:
    """Phase 2 动态多轮：task-a → human-1 → task-a（回边成环）。
    human-1 每轮无答案→路由 END 暂停；resume 带一份答案→消费一次→绕回→再次 waiting。"""
    return WorkflowDefinition(
        workflowId=42,
        name="cyclic human input workflow",
        nodes=[
            RuntimeNode(id="task-a", type="TASK", label="Judge", config={}),
            RuntimeNode(
                id="human-1",
                type="HUMAN_INPUT",
                label="Ask",
                config={
                    "inputKey": "budget",
                    "questionTemplate": "第{{human.round}}轮：请补充信息",
                    "inputType": "text",
                    "nodeAlias": "askRound",
                },
            ),
        ],
        edges=[
            RuntimeEdge(source="task-a", target="human-1"),
            RuntimeEdge(source="human-1", target="task-a"),
        ],
    )


def test_build_events_cyclic_human_input_multi_round_pause_resume(tmp_path):
    """Phase 2 e2e：环内 HUMAN_INPUT 每轮重新暂停/续跑。三轮（首次问 + 答1再问 + 答2再问）。"""
    store = FileCheckpointStore(tmp_path)

    # 第一轮：首次执行，无答案 → 到 human-1 即 waiting
    events_r1 = build_events(
        ExecutionRequest(
            executionId="3001",
            rootExecutionId="3001",
            userId=7,
            workflow=_cyclic_human_input_workflow(),
            input={"message": "hello"},
            runtime={"checkpoint": True},
        ),
        checkpoint_store=store,
    )
    completed_r1 = [e.nodeId for e in events_r1 if e.type == "NODE_COMPLETED"]
    assert completed_r1 == ["task-a"]  # human-1 未消费，不发射
    waiting_r1 = [e for e in events_r1 if e.type == "WAITING_INPUT"]
    assert len(waiting_r1) == 1 and waiting_r1[0].nodeId == "human-1"
    assert store.load("checkpoint-3001")["pausedAtNodeId"] == "human-1"

    # 第二轮：resume 带 budget=ans1 → 消费 → 绕回 task-a → 再次 waiting（第 2 次问）
    events_r2 = build_events(
        ExecutionRequest(
            executionId="3002",
            rootExecutionId="3002",
            userId=7,
            workflow=_cyclic_human_input_workflow(),
            input={"message": "hello"},
            runtime={
                "checkpoint": True,
                "resumeFromCheckpointRef": "checkpoint-3001",
                "userInput": {"budget": "ans1"},
            },
        ),
        checkpoint_store=store,
    )
    completed_r2 = [e.nodeId for e in events_r2 if e.type == "NODE_COMPLETED"]
    # 从 human-1 消费处发射到下一个 waiting 之前：human-1(消费 ans1) + task-a(再判)
    assert completed_r2 == ["human-1", "task-a"]
    human_r2 = [e for e in events_r2 if e.type == "NODE_COMPLETED" and e.nodeId == "human-1"][0]
    assert human_r2.output["value"] == "ans1"
    assert human_r2.output["status"] == "SUCCESS"
    waiting_r2 = [e for e in events_r2 if e.type == "WAITING_INPUT"]
    assert len(waiting_r2) == 1 and waiting_r2[0].nodeId == "human-1"
    assert store.load("checkpoint-3002")["pausedAtNodeId"] == "human-1"

    # 第三轮：resume 带 budget=ans2 → 同形（证明 inputVisits 每轮 reset，不会因上一轮累加而卡死）
    events_r3 = build_events(
        ExecutionRequest(
            executionId="3003",
            rootExecutionId="3003",
            userId=7,
            workflow=_cyclic_human_input_workflow(),
            input={"message": "hello"},
            runtime={
                "checkpoint": True,
                "resumeFromCheckpointRef": "checkpoint-3002",
                "userInput": {"budget": "ans2"},
            },
        ),
        checkpoint_store=store,
    )
    completed_r3 = [e.nodeId for e in events_r3 if e.type == "NODE_COMPLETED"]
    assert completed_r3 == ["human-1", "task-a"]
    human_r3 = [e for e in events_r3 if e.type == "NODE_COMPLETED" and e.nodeId == "human-1"][0]
    assert human_r3.output["value"] == "ans2"
    assert [e for e in events_r3 if e.type == "WAITING_INPUT"][0].nodeId == "human-1"


def test_build_events_cyclic_human_input_exits_when_branch_skips_it(tmp_path):
    """Phase 2：环 resume 时若本轮走的分支不再经过暂停的 HUMAN_INPUT（信息够了→出口），
    应正常发射到结束、EXECUTION_COMPLETED，不误发 WAITING_INPUT。"""
    store = FileCheckpointStore(tmp_path)

    # 用条件分支：有 budget → end；无 budget → human-1（回 task-a 成环）
    workflow = WorkflowDefinition(
        workflowId=42,
        name="cyclic with exit branch",
        nodes=[
            RuntimeNode(
                id="cond-1",
                type="CONDITION",
                label="Enough?",
                config={
                    "defaultTarget": "human-1",
                    "conditions": [
                        {"name": "yes", "target": "end-1", "field": "budget", "operator": "exists"}
                    ],
                },
            ),
            RuntimeNode(id="human-1", type="HUMAN_INPUT", label="Ask", config={"inputKey": "budget"}),
            RuntimeNode(id="end-1", type="END", label="End", config={}),
        ],
        edges=[
            RuntimeEdge(source="cond-1", target="human-1"),
            RuntimeEdge(source="cond-1", target="end-1"),
            RuntimeEdge(source="human-1", target="cond-1"),
        ],
    )

    # 首轮：无 budget → cond-1 default→human-1 → waiting
    build_events(
        ExecutionRequest(
            executionId="4001",
            rootExecutionId="4001",
            userId=7,
            workflow=workflow,
            input={"message": "hello"},
            runtime={"checkpoint": True},
        ),
        checkpoint_store=store,
    )
    assert store.load("checkpoint-4001")["pausedAtNodeId"] == "human-1"

    # resume 带 budget：cond-1 命中 yes→end-1，不再经过 human-1 → EXECUTION_COMPLETED
    events = build_events(
        ExecutionRequest(
            executionId="4002",
            rootExecutionId="4002",
            userId=7,
            workflow=workflow,
            input={"message": "hello"},
            runtime={
                "checkpoint": True,
                "resumeFromCheckpointRef": "checkpoint-4001",
                "userInput": {"budget": "ans-final"},
            },
        ),
        checkpoint_store=store,
    )
    assert [e for e in events if e.type == "WAITING_INPUT"] == []
    assert events[-1].type == "EXECUTION_COMPLETED"
