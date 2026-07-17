from __future__ import annotations

from collections import defaultdict, deque
import operator
from typing import Annotated, Any, TypedDict

from langgraph.graph import END, START, StateGraph

from app.models import RuntimeNode, WorkflowDefinition
from app.node_runtime import resolve_source


class RuntimeState(TypedDict):
    input: Annotated[dict[str, Any], merge_input]
    visited: Annotated[list[str], operator.add]
    outputs: Annotated[dict[str, dict[str, Any]], merge_outputs]
    # Phase 2 环支持：HUMAN_INPUT 按轮计访问次数（key=inputKey → 已消费次数）。
    # reducer 取大，避免并行分支覆盖；每轮 node_runner 返回 {inputKey: visits+1}。
    inputVisits: Annotated[dict[str, int], merge_max]


def merge_input(left: dict[str, Any], right: dict[str, Any]) -> dict[str, Any]:
    return left or right or {}


def merge_outputs(left: dict[str, dict[str, Any]], right: dict[str, dict[str, Any]]) -> dict[str, dict[str, Any]]:
    return {**(left or {}), **(right or {})}


def merge_max(left: dict[str, int], right: dict[str, int]) -> dict[str, int]:
    result = dict(left or {})
    for key, value in (right or {}).items():
        if key not in result or value > result[key]:
            result[key] = value
    return result


def compile_workflow_graph(workflow: WorkflowDefinition):
    ordered_ids = topological_node_ids(workflow)
    nodes_by_id = {node.id: node for node in workflow.nodes}
    branching_ids = {node.id for node in workflow.nodes if is_branching_node(node)}
    # Phase 2：HUMAN_INPUT 改条件路由节点（consume 走正常后继 / wait 路由 END），
    # 与 CONDITION/ROUTER 一样走 add_conditional_edges，并跳过普通 add_edge / exit 自动连 END。
    human_input_ids = {node.id for node in workflow.nodes if node.type.upper() == "HUMAN_INPUT"}
    conditional_node_ids = branching_ids | human_input_ids
    join_node_ids = {node.id for node in workflow.nodes if node.type.upper() == "JOIN"}

    graph = StateGraph(RuntimeState)
    for node_id in ordered_ids:
        graph.add_node(node_id, node_runner(nodes_by_id[node_id], workflow))

    entry_ids = entry_node_ids(workflow, ordered_ids)
    exit_ids = exit_node_ids(workflow, ordered_ids)
    for node_id in entry_ids:
        graph.add_edge(START, node_id)
    for edge in workflow.edges:
        if edge.source in nodes_by_id and edge.target in nodes_by_id:
            if edge.source not in conditional_node_ids and edge.target not in join_node_ids:
                graph.add_edge(edge.source, edge.target)
    for node_id in branching_ids:
        route_map = branch_route_map(workflow, nodes_by_id[node_id])
        graph.add_conditional_edges(node_id, branch_router(nodes_by_id[node_id], route_map), route_map)
    for node_id in human_input_ids:
        route_map = human_input_route_map(workflow, nodes_by_id[node_id])
        graph.add_conditional_edges(node_id, human_input_router(nodes_by_id[node_id], route_map), route_map)
    for node_id in join_node_ids:
        sources = join_source_node_ids(workflow, node_id)
        if len(sources) > 1:
            graph.add_edge(sources, node_id)
        elif len(sources) == 1:
            graph.add_edge(sources[0], node_id)
    for node_id in exit_ids:
        if node_id in conditional_node_ids:
            # 条件路由节点已含 END 路由（HUMAN_INPUT 的 wait 分支 / 分支节点的 default），
            # 再 add_edge(node,END) 会与 conditional_edges 冲突。
            continue
        graph.add_edge(node_id, END)

    return graph.compile()


def node_runner(node: RuntimeNode, workflow: WorkflowDefinition):
    def run(state: RuntimeState) -> RuntimeState:
        if node.config.get("fail") is True:
            if has_continue_failure_policy(workflow, node.id):
                return {
                    "visited": [node.id],
                    "outputs": {
                        node.id: {
                            "nodeId": node.id,
                            "nodeType": node.type.upper(),
                            "nodeAlias": node_alias(node),
                            "status": "FAILED",
                            "errorMessage": f"node {node.id} failed by config",
                        }
                    },
                }
            raise RuntimeError(f"node {node.id} failed by config")
        source_type, source_id = resolve_source(node)
        selected_target = None
        selected_route = None
        joined_outputs = None
        joined_node_ids = None
        if is_branching_node(node):
            route_map = branch_route_map(workflow, node)
            route_key = select_branch_key(node, route_map, state)
            selected_route = route_key
            selected_target = route_map[route_key]
        if node.type.upper() == "JOIN":
            joined_node_ids = join_source_node_ids(workflow, node.id)
            joined_outputs = {
                node_id: state.get("outputs", {}).get(node_id)
                for node_id in joined_node_ids
                if node_id in state.get("outputs", {})
            }
        if node.type.upper() == "HUMAN_INPUT":
            input_key = str(node.config.get("inputKey") or node.id)
            input_data = state.get("input", {})
            input_visits = state.get("inputVisits", {})
            visits = int(input_visits.get(input_key, 0))
            # 本 invoke 提供的答案数（userInput 注入到 state.input）。单答案/轮模型：在则 1 否则 0。
            provided_count = 1 if input_key in input_data else 0
            # Phase 2 路线 B：本轮仍有未消费答案 → 消费、走正常后继；否则 → WAITING_INPUT、route END 终止 invoke。
            if visits < provided_count:
                value = input_data.get(input_key)
                status = "SUCCESS"
                new_visits = visits + 1
                message = f"{node.label or node.id} input resolved"
            else:
                value = None
                status = "WAITING_INPUT"
                new_visits = visits
                message = f"{node.label or node.id} awaiting human input"
            output = {
                "nodeId": node.id,
                "nodeType": "HUMAN_INPUT",
                "nodeAlias": node_alias(node),
                "status": status,
                "inputKey": input_key,
                "inputType": node.config.get("inputType") or "text",
                "value": value,
                "options": node.config.get("options"),
                "required": node.config.get("required", True),
                "question": node.config.get("questionTemplate") or "",
                "message": message,
            }
            return {
                "visited": [node.id],
                "outputs": {node.id: output},
                "inputVisits": {input_key: new_visits},
            }
        if is_input_value_node(node):
            input_key = node.config.get("inputKey") or node.id
            input_data = state.get("input", {})
            output = {
                "nodeId": node.id,
                "nodeType": node.type.upper(),
                "nodeAlias": node_alias(node),
                "status": "SUCCESS",
                "inputKey": input_key,
                "inputType": node.config.get("inputType") or "text",
                "value": input_data.get(input_key, node.config.get("defaultValue")),
                "message": f"{node.label or node.id} input resolved",
            }
            return {
                "visited": [node.id],
                "outputs": {node.id: output},
            }
        output = {
            "nodeId": node.id,
            "nodeType": node.type.upper(),
            "nodeAlias": node_alias(node),
            "sourceType": source_type,
            "sourceId": source_id,
            "status": "SUCCESS",
            "selectedRoute": selected_route,
            "selectedTarget": selected_target,
            "reason": router_reason(node, selected_route),
            "confidence": router_confidence(node, selected_route),
            "joinedNodeIds": joined_node_ids,
            "joinedOutputs": joined_outputs,
            "message": f"{node.label or node.id} completed by LangGraph",
        }
        return {
            "visited": [node.id],
            "outputs": {node.id: output},
        }

    return run


def node_alias(node: RuntimeNode) -> str:
    return str(node.config.get("nodeAlias") or node.id)


def is_input_value_node(node: RuntimeNode) -> bool:
    return node.type.upper() == "INPUT" or (node.type.upper() == "START" and bool(node.config.get("inputKey")))


def has_continue_failure_policy(workflow: WorkflowDefinition, node_id: str) -> bool:
    parallel_sources = [
        edge.source
        for edge in workflow.edges
        if edge.target == node_id
    ]
    nodes_by_id = {node.id: node for node in workflow.nodes}
    return any(
        nodes_by_id.get(source)
        and nodes_by_id[source].type.upper() == "PARALLEL"
        and nodes_by_id[source].config.get("branchFailurePolicy") == "continue"
        for source in parallel_sources
    )


def branch_router(node: RuntimeNode, route_map: dict[str, str]):
    def route(state: RuntimeState) -> str:
        return select_branch_key(node, route_map, state)

    return route


def human_input_route_map(workflow: WorkflowDefinition, node: RuntimeNode) -> dict[str, str]:
    # HUMAN_INPUT 两条路由：consume=正常后继（首个出边 target），wait=END（终止 invoke 等答案）。
    valid_ids = {n.id for n in workflow.nodes}
    targets = [
        edge.target
        for edge in workflow.edges
        if edge.source == node.id and edge.target in valid_ids
    ]
    normal = targets[0] if targets else END
    return {"consume": normal, "wait": END}


def human_input_router(node: RuntimeNode, route_map: dict[str, str]):
    def route(state: RuntimeState) -> str:
        # node_runner 刚把本次输出写入 state.outputs；WAITING_INPUT→wait(END)，否则 consume。
        output = state.get("outputs", {}).get(node.id, {})
        return "wait" if output.get("status") == "WAITING_INPUT" else "consume"

    return route


def branch_route_map(workflow: WorkflowDefinition, node: RuntimeNode) -> dict[str, str]:
    if node.type.upper() in {"ROUTER", "LLM_ROUTER"}:
        return router_route_map(workflow, node)
    return condition_route_map(workflow, node.id)


def condition_route_map(workflow: WorkflowDefinition, node_id: str) -> dict[str, str]:
    node = next((candidate for candidate in workflow.nodes if candidate.id == node_id), None)
    if node and node.config.get("conditions"):
        connected_targets = {edge.target for edge in workflow.edges if edge.source == node_id}
        mapping = {}
        for condition in node.config.get("conditions", []):
            target = condition.get("target")
            name = condition.get("name") or target
            if target in connected_targets:
                mapping[str(name)] = target
        default_target = node.config.get("defaultTarget")
        if default_target in connected_targets:
            mapping["default"] = default_target
        return mapping
    mapping = {}
    for edge in workflow.edges:
        if edge.source == node_id and edge.condition:
            mapping[str(edge.condition)] = edge.target
    return mapping


def router_route_map(workflow: WorkflowDefinition, node: RuntimeNode) -> dict[str, str]:
    connected_targets = {edge.target for edge in workflow.edges if edge.source == node.id}
    mapping = {}
    for route in node.config.get("routes", []):
        target = route.get("target")
        name = route.get("name") or target
        if target in connected_targets:
            mapping[str(name)] = target
    default_target = node.config.get("defaultTarget")
    if default_target in connected_targets:
        mapping["default"] = default_target
    return mapping


def select_branch_key(node: RuntimeNode, route_map: dict[str, str], state: RuntimeState) -> str:
    if node.type.upper() in {"ROUTER", "LLM_ROUTER"}:
        return select_router_key(node, route_map, state)
    return select_condition_key(node, route_map, state)


def select_condition_key(node: RuntimeNode, route_map: dict[str, str], state: RuntimeState) -> str:
    if node.config.get("conditions"):
        return select_structured_condition_key(node, route_map, state)
    input_path = node.config.get("inputPath")
    actual = read_path(state.get("input", {}), input_path)
    for condition_key in route_map:
        if condition_matches(condition_key, actual, state.get("input", {})):
            return condition_key
    default_target = node.config.get("defaultTarget")
    for condition_key, target in route_map.items():
        if target == default_target:
            return condition_key
    raise ValueError(f"condition node {node.id} has no matching route")


def select_structured_condition_key(node: RuntimeNode, route_map: dict[str, str], state: RuntimeState) -> str:
    input_data = state.get("input", {})
    for condition in node.config.get("conditions", []):
        name = str(condition.get("name") or condition.get("target"))
        if name in route_map and evaluate_condition_group(condition, input_data):
            return name
    if "default" in route_map:
        return "default"
    raise ValueError(f"condition node {node.id} has no matching route")


def select_router_key(node: RuntimeNode, route_map: dict[str, str], state: RuntimeState) -> str:
    if node.type.upper() == "LLM_ROUTER":
        selected = node.config.get("mockSelectedRoute")
        if selected and str(selected) in route_map:
            return str(selected)
        if "default" in route_map:
            return "default"
        raise ValueError(f"llm router node {node.id} has no matching route")
    input_data = state.get("input", {})
    for route in node.config.get("routes", []):
        name = str(route.get("name") or route.get("target"))
        target = route.get("target")
        condition = route.get("condition")
        if name in route_map and target == route_map[name] and condition and condition_matches(str(condition), None, input_data):
            return name
    if "default" in route_map:
        return "default"
    raise ValueError(f"router node {node.id} has no matching route")


def router_reason(node: RuntimeNode, selected_route: str | None) -> str | None:
    if node.type.upper() != "LLM_ROUTER" or selected_route is None:
        return None
    return f"mock LLM router selected {selected_route}"


def router_confidence(node: RuntimeNode, selected_route: str | None) -> float | None:
    if node.type.upper() != "LLM_ROUTER" or selected_route is None:
        return None
    return float(node.config.get("mockConfidence", 1.0))


def is_branching_node(node: RuntimeNode) -> bool:
    return node.type.upper() in {"CONDITION", "ROUTER", "LLM_ROUTER"}


def join_source_node_ids(workflow: WorkflowDefinition, node_id: str) -> list[str]:
    return [edge.source for edge in workflow.edges if edge.target == node_id]


def condition_matches(condition: str, actual: Any, input_data: dict[str, Any]) -> bool:
    if actual is not None and str(condition) == str(actual):
        return True

    parts = condition.split()
    if len(parts) == 2 and parts[1] == "exists":
        return read_path(input_data, parts[0]) is not None
    if len(parts) < 3:
        return False

    left = read_path(input_data, parts[0])
    operator = parts[1]
    right = " ".join(parts[2:])
    if operator == "contains":
        return contains(left, right)
    if operator in {"==", "!=", ">", ">=", "<", "<="}:
        return compare(left, operator, parse_literal(right))
    return False


def evaluate_condition_group(condition: dict[str, Any], input_data: dict[str, Any]) -> bool:
    if "all" in condition:
        return all(evaluate_condition_group(item, input_data) for item in condition["all"])
    if "any" in condition:
        return any(evaluate_condition_group(item, input_data) for item in condition["any"])
    if "not" in condition:
        return not evaluate_condition_group(condition["not"], input_data)
    field = condition.get("field")
    operator = condition.get("operator", "==")
    left = read_path(input_data, field)
    if operator == "exists":
        return left is not None
    if operator == "contains":
        return contains(left, condition.get("value"))
    if operator in {"==", "!=", ">", ">=", "<", "<="}:
        return compare(left, operator, condition.get("value"))
    return False


def contains(left: Any, right: Any) -> bool:
    if left is None:
        return False
    if isinstance(left, (list, tuple, set)):
        return str(right) in {str(item) for item in left}
    return str(right) in str(left)


def compare(left: Any, operator: str, right: Any) -> bool:
    if operator == "==":
        return normalize(left) == normalize(right)
    if operator == "!=":
        return normalize(left) != normalize(right)
    left_number = as_number(left)
    right_number = as_number(right)
    if left_number is None or right_number is None:
        return False
    if operator == ">":
        return left_number > right_number
    if operator == ">=":
        return left_number >= right_number
    if operator == "<":
        return left_number < right_number
    if operator == "<=":
        return left_number <= right_number
    return False


def normalize(value: Any) -> Any:
    if isinstance(value, str):
        return value.strip("'\"")
    return value


def parse_literal(value: str) -> Any:
    stripped = value.strip().strip("'\"")
    number = as_number(stripped)
    return number if number is not None else stripped


def as_number(value: Any) -> float | None:
    try:
        if value is None or value == "":
            return None
        return float(value)
    except (TypeError, ValueError):
        return None


def read_path(data: dict[str, Any], path: str | None) -> Any:
    if not path:
        return None
    current: Any = data
    for part in path.split("."):
        if not isinstance(current, dict) or part not in current:
            return None
        current = current[part]
    return current


def topological_node_ids(workflow: WorkflowDefinition) -> list[str]:
    nodes_by_id = {node.id: node for node in workflow.nodes}
    incoming_count = {node.id: 0 for node in workflow.nodes}
    outgoing: dict[str, list[str]] = defaultdict(list)
    for edge in workflow.edges:
        if edge.source in nodes_by_id and edge.target in nodes_by_id:
            outgoing[edge.source].append(edge.target)
            incoming_count[edge.target] += 1

    queue = deque(node.id for node in workflow.nodes if incoming_count[node.id] == 0)
    ordered: list[str] = []
    while queue:
        node_id = queue.popleft()
        ordered.append(node_id)
        for target in outgoing[node_id]:
            incoming_count[target] -= 1
            if incoming_count[target] == 0:
                queue.append(target)

    if len(ordered) != len(workflow.nodes):
        # Phase 2 环支持：Kahn 不会释放环内节点（incoming_count 永不为 0）。
        # 追加它们以便 add_node 不漏（LangGraph 原生支持环；编译只看 add_node + add_edge，与顺序无关）。
        # 按节点声明顺序追加，保持稳定。环内真正的"逐轮暂停/恢复"由 runtime_executor + HUMAN_INPUT 条件路由处理。
        ordered_set = set(ordered)
        for node in workflow.nodes:
            if node.id not in ordered_set:
                ordered.append(node.id)
    return ordered


def entry_node_ids(workflow: WorkflowDefinition, ordered_ids: list[str]) -> list[str]:
    targets = {edge.target for edge in workflow.edges}
    entries = [node_id for node_id in ordered_ids if node_id not in targets]
    return entries or ordered_ids[:1]


def exit_node_ids(workflow: WorkflowDefinition, ordered_ids: list[str]) -> list[str]:
    sources = {edge.source for edge in workflow.edges}
    exits = [node_id for node_id in ordered_ids if node_id not in sources]
    return exits or ordered_ids[-1:]
