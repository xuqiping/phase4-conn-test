from __future__ import annotations

from app.checkpoint_store import CheckpointStore
from app.models import ExecutionEvent, ExecutionRequest, RuntimeNode
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
    restored_visited = set(restored_state.get("visited", [])) if restored_state else set()
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
    emitted_node_ids = [node_id for node_id in graph_result["visited"] if node_id not in restored_visited]
    approval_node = first_waiting_approval_node(request, emitted_node_ids, nodes_by_id)
    if approval_node:
        emitted_node_ids = emitted_node_ids[:emitted_node_ids.index(approval_node.id)]
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
