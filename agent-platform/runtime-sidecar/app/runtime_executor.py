from __future__ import annotations

import json
import re
from collections import defaultdict, deque
from typing import Any

from app.checkpoint_store import CheckpointStore
from app.models import ExecutionEvent, ExecutionRequest, RuntimeNode, WorkflowDefinition
from app.graph_compiler import compile_workflow_graph
from app.callback_client import RuntimeNodeCallbackRequest, RuntimeNodeCallbackResponse, execute_runtime_callback
from app.node_runtime import resolve_source


def build_events(
    request: ExecutionRequest,
    checkpoint_store: CheckpointStore | None = None,
    callback_executor=execute_runtime_callback,
) -> list[ExecutionEvent]:
    return list(iter_events(request, checkpoint_store, callback_executor))


def iter_events(
    request: ExecutionRequest,
    checkpoint_store: CheckpointStore | None = None,
    callback_executor=execute_runtime_callback,
):
    metadata = {
        "traceId": request.runtime.get("traceId"),
        "externalThreadId": f"sidecar-thread-{request.executionId}",
        "runtime": "runtime-sidecar",
        "engine": "langgraph",
    }
    resume_checkpoint_ref = request.runtime.get("resumeFromCheckpointRef")
    restored_state = checkpoint_store.load(resume_checkpoint_ref) if checkpoint_store and resume_checkpoint_ref else None
    user_input = request.runtime.get("userInput") or {}
    if request.runtime.get("checkpoint") is True:
        metadata["checkpointRef"] = f"checkpoint-{request.executionId}"
    if resume_checkpoint_ref:
        metadata["resumeFromCheckpointRef"] = resume_checkpoint_ref
    if restored_state is not None:
        metadata["checkpointRestored"] = True
        metadata["restoredCheckpointRef"] = resume_checkpoint_ref
    yield event(request, "EXECUTION_STARTED", "RUNNING", metadata=metadata)

    graph = compile_workflow_graph(request.workflow)
    initial_state = restored_state or {"input": request.input, "visited": [], "outputs": {}}
    if restored_state is not None:
        initial_state = inject_user_input(initial_state, user_input)
    restored_visited = set(restored_state.get("visited", [])) if restored_state else set()
    restored_pause_id = restored_state.get("pausedAtNodeId") if restored_state else None
    is_input_resume = bool(restored_pause_id and restored_pause_id in restored_visited)
    try:
        graph_result = graph.invoke(initial_state)
    except Exception as exc:
        failed_node_id = failed_node_id_from_error(exc)
        if checkpoint_store and request.runtime.get("checkpoint") is True:
            checkpoint_store.save(f"checkpoint-{request.executionId}", initial_state)
        failure_metadata = dict(metadata)
        failure_metadata["failedNodeId"] = failed_node_id
        failure_metadata["errorMessage"] = str(exc)
        if request.runtime.get("checkpoint") is True:
            failure_metadata["recoveryCheckpointRef"] = f"checkpoint-{request.executionId}"
        yield event(request, "EXECUTION_FAILED", "FAILED", node_id=failed_node_id, metadata=failure_metadata)
        return
    if checkpoint_store and request.runtime.get("checkpoint") is True:
        checkpoint_store.save(f"checkpoint-{request.executionId}", graph_result)
    nodes_by_id = {node.id: node for node in request.workflow.nodes}
    approval_node = None
    pending_input = None
    if is_input_resume:
        descendant_set = descendants(request.workflow, restored_pause_id)
        emitted_node_ids = unique_ordered(
            node_id for node_id in graph_result["visited"] if node_id in descendant_set
        )
        next_input = first_waiting_input_node(request, emitted_node_ids, nodes_by_id, user_input)
        if next_input:
            emitted_node_ids = emitted_node_ids[: emitted_node_ids.index(next_input.id)]
            pending_input = next_input
    else:
        emitted_node_ids = [node_id for node_id in graph_result["visited"] if node_id not in restored_visited]
        approval_node = first_waiting_approval_node(request, emitted_node_ids, nodes_by_id)
        if approval_node:
            emitted_node_ids = emitted_node_ids[:emitted_node_ids.index(approval_node.id)]
        else:
            pending_input = first_waiting_input_node(request, emitted_node_ids, nodes_by_id, user_input)
            if pending_input:
                emitted_node_ids = emitted_node_ids[:emitted_node_ids.index(pending_input.id)]
    for node_id in emitted_node_ids:
        node = nodes_by_id[node_id]
        source_type, source_id = resolve_source(node)
        node_input = node_event_input(request, node, graph_result)
        output = graph_result["outputs"][node.id]
        yield event(
            request,
            "NODE_STARTED",
            "RUNNING",
            node_id=node.id,
            source_type=source_type,
            source_id=source_id,
            input_data=node_input,
            metadata=metadata,
        )
        if (
            node.type.upper() in {"SKILL", "AGENT_REF", "RETRIEVAL"}
            and source_type
            and source_id
            and request.runtime.get("javaCallbackBaseUrl")
        ):
            try:
                output = callback_output(request, node, source_type, source_id, metadata, graph_result, callback_executor)
                graph_result["outputs"][node.id] = output
            except Exception as exc:
                if checkpoint_store and request.runtime.get("checkpoint") is True:
                    checkpoint_store.save(f"checkpoint-{request.executionId}", graph_result)
                failure_metadata = dict(metadata)
                failure_metadata["failedNodeId"] = node.id
                failure_metadata["errorMessage"] = str(exc)
                if request.runtime.get("checkpoint") is True:
                    failure_metadata["recoveryCheckpointRef"] = f"checkpoint-{request.executionId}"
                yield event(
                    request,
                    "EXECUTION_FAILED",
                    "FAILED",
                    node_id=node.id,
                    source_type=source_type,
                    source_id=source_id,
                    metadata=failure_metadata,
                )
                return
        yield event(
            request,
            "NODE_COMPLETED",
            "SUCCESS",
            node_id=node.id,
            source_type=source_type,
            source_id=source_id,
            output=output,
            metadata=metadata,
        )

    if pending_input:
        if checkpoint_store and request.runtime.get("checkpoint") is True:
            augmented_state = dict(graph_result)
            augmented_state["pausedAtNodeId"] = pending_input.id
            checkpoint_store.save(f"checkpoint-{request.executionId}", augmented_state)
        input_metadata = dict(metadata)
        input_metadata["inputKey"] = pending_input.config.get("inputKey") or pending_input.id
        input_metadata["question"] = render_template(
            pending_input.config.get("questionTemplate") or "",
            graph_result.get("outputs", {}),
        )
        input_metadata["inputType"] = pending_input.config.get("inputType") or "text"
        input_metadata["options"] = pending_input.config.get("options")
        input_metadata["required"] = pending_input.config.get("required", True)
        input_metadata["placeholder"] = pending_input.config.get("placeholder")
        input_metadata["inputCheckpointRef"] = f"checkpoint-{request.executionId}"
        yield event(
            request,
            "WAITING_INPUT",
            "WAITING_INPUT",
            node_id=pending_input.id,
            metadata=input_metadata,
        )
        return

    if approval_node:
        approval_metadata = dict(metadata)
        approval_metadata["approvalKey"] = approval_node.config.get("approvalKey") or approval_node.id
        if request.runtime.get("checkpoint") is True:
            approval_metadata["approvalCheckpointRef"] = f"checkpoint-{request.executionId}"
        yield event(
            request,
            "WAITING_APPROVAL",
            "WAITING_APPROVAL",
            node_id=approval_node.id,
            metadata=approval_metadata,
        )
        return

    yield event(request, "EXECUTION_COMPLETED", "SUCCESS", metadata=metadata)


def first_waiting_approval_node(
    request: ExecutionRequest,
    emitted_node_ids: list[str],
    nodes_by_id: dict[str, RuntimeNode],
) -> RuntimeNode | None:
    if request.runtime.get("approvalDecision") == "approved":
        return None
    for node_id in emitted_node_ids:
        node = nodes_by_id[node_id]
        if node.type.upper() == "HUMAN_APPROVAL":
            return node
    return None


def failed_node_id_from_error(error: Exception) -> str | None:
    message = str(error)
    if message.startswith("node ") and " failed" in message:
        return message.split(" ", 2)[1]
    return None


def event(
    request: ExecutionRequest,
    event_type: str,
    status: str,
    *,
    node_id: str | None = None,
    source_type: str | None = None,
    source_id: int | None = None,
    input_data: dict | None = None,
    output: dict | None = None,
    metadata: dict | None = None,
) -> ExecutionEvent:
    return ExecutionEvent(
        executionId=request.executionId,
        rootExecutionId=request.rootExecutionId,
        parentExecutionId=request.parentExecutionId,
        nodeId=node_id,
        type=event_type,
        status=status,
        sourceType=source_type,
        sourceId=source_id,
        input=input_data,
        output=output,
        metadata=metadata or {},
    )


def callback_output(
    request: ExecutionRequest,
    node: RuntimeNode,
    source_type: str,
    source_id: int,
    metadata: dict,
    graph_result: dict,
    callback_executor,
) -> dict:
    java_base_url = request.runtime["javaCallbackBaseUrl"]
    callback_metadata = dict(metadata)
    callback_metadata["nodeConfig"] = node.config
    response: RuntimeNodeCallbackResponse = callback_executor(
        java_base_url,
        RuntimeNodeCallbackRequest(
            executionId=request.executionId,
            rootExecutionId=request.rootExecutionId,
            parentExecutionId=request.parentExecutionId,
            nodeId=node.id,
            sourceType=source_type,
            sourceId=source_id,
            userId=request.userId,
            input=callback_input(request, graph_result),
            traceId=request.runtime.get("traceId"),
            metadata=callback_metadata,
        ),
    )
    if not response.success:
        raise RuntimeError(response.error or f"node {node.id} callback failed")
    output = dict(response.output)
    output["nodeId"] = node.id
    output["nodeType"] = node.type.upper()
    output["nodeAlias"] = node_alias(node)
    if "outputKey" not in output and node.config.get("outputKey"):
        output["outputKey"] = node.config.get("outputKey")
    output["sourceType"] = source_type
    output["sourceId"] = source_id
    output["status"] = "SUCCESS"
    output["selectedSkillIds"] = response.selectedSkillIds
    output["stepOutputs"] = response.stepOutputs
    output["metadata"] = response.metadata
    return output


def node_event_input(request: ExecutionRequest, node: RuntimeNode, graph_result: dict) -> dict:
    if node.type.upper() in {"SKILL", "AGENT_REF"}:
        return callback_input(request, graph_result)
    if node.type.upper() == "INPUT":
        input_key = node.config.get("inputKey") or node.id
        return {str(input_key): (request.input or {}).get(input_key, node.config.get("defaultValue"))}
    return dict(request.input or {})


def node_alias(node: RuntimeNode) -> str:
    return str(node.config.get("nodeAlias") or node.id)


def callback_input(request: ExecutionRequest, graph_result: dict) -> dict:
    merged = dict(request.input or {})
    for output in graph_result.get("outputs", {}).values():
        if not isinstance(output, dict):
            continue
        node_alias = output.get("nodeAlias")
        input_key = output.get("inputKey")
        if input_key and output.get("value") is not None:
            merged[str(input_key)] = output.get("value")
            if node_alias:
                merged[f"{node_alias}.{input_key}"] = output.get("value")
        output_key = output.get("outputKey")
        if output_key and output.get("text") is not None:
            merged[str(output_key)] = output.get("text")
            if node_alias:
                merged[f"{node_alias}.{output_key}"] = output.get("text")
        for step_output in output.get("stepOutputs") or []:
            if isinstance(step_output, dict) and step_output.get("outputKey") and step_output.get("output") is not None:
                merged[str(step_output["outputKey"])] = step_output["output"]
                if node_alias:
                    merged[f"{node_alias}.{step_output['outputKey']}"] = step_output["output"]
    return merged


def unique_ordered(items):
    seen: set[str] = set()
    result: list[str] = []
    for item in items:
        if item not in seen:
            seen.add(item)
            result.append(item)
    return result


def inject_user_input(state: dict, user_input: dict[str, Any]) -> dict:
    merged_input = dict(state.get("input") or {})
    for key, value in (user_input or {}).items():
        merged_input[str(key)] = value
    new_state = dict(state)
    new_state["input"] = merged_input
    return new_state


def first_waiting_input_node(
    request: ExecutionRequest,
    node_ids: list[str],
    nodes_by_id: dict[str, RuntimeNode],
    user_input: dict[str, Any],
) -> RuntimeNode | None:
    answered_keys = {str(key) for key in (user_input or {})}
    for node_id in node_ids:
        node = nodes_by_id.get(node_id)
        if node and node.type.upper() == "HUMAN_INPUT":
            input_key = str(node.config.get("inputKey") or node.id)
            if input_key not in answered_keys:
                return node
    return None


def descendants(workflow: WorkflowDefinition, node_id: str) -> set[str]:
    valid_ids = {node.id for node in workflow.nodes}
    adjacency: dict[str, list[str]] = defaultdict(list)
    for edge in workflow.edges:
        if edge.source in valid_ids and edge.target in valid_ids:
            adjacency[edge.source].append(edge.target)
    seen = {node_id}
    queue = deque([node_id])
    while queue:
        current = queue.popleft()
        for nxt in adjacency.get(current, []):
            if nxt not in seen:
                seen.add(nxt)
                queue.append(nxt)
    return seen


_TEMPLATE_VAR = re.compile(r"\{\{\s*([^}]+?)\s*\}\}")


def render_template(template: str | None, outputs: dict[str, Any]) -> str:
    if not template:
        return ""
    outputs = outputs or {}

    def resolve(match: re.Match) -> str:
        path = match.group(1).strip()
        parts = path.split(".")
        alias = parts[0]
        field = parts[1] if len(parts) > 1 else None
        for raw_output in outputs.values():
            output = raw_output if isinstance(raw_output, dict) else {}
            if output.get("nodeAlias") == alias or output.get("nodeId") == alias:
                if field:
                    value = output.get(field)
                    if value is None and field in {"output", "value", "text"}:
                        for fallback in ("value", "text", "output"):
                            if output.get(fallback) is not None:
                                value = output.get(fallback)
                                break
                else:
                    value = output.get("value")
                    if value is None:
                        value = output.get("text")
                if value is None:
                    return ""
                return str(value) if not isinstance(value, (dict, list)) else json.dumps(value, ensure_ascii=False)
        return ""

    return _TEMPLATE_VAR.sub(resolve, template)
