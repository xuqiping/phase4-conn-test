import pytest

from app.graph_compiler import compile_workflow_graph
from app.models import RuntimeEdge, RuntimeNode, WorkflowDefinition


def workflow(nodes, edges):
    return WorkflowDefinition(
        version="2026-06-03",
        workflowId=1,
        name="graph compiler test",
        nodes=nodes,
        edges=edges,
    )


def node(node_id, node_type="SKILL", **config):
    return RuntimeNode(id=node_id, type=node_type, label=node_id, config=config)


def edge(source, target):
    return RuntimeEdge(source=source, target=target)


def conditional_edge(source, target, condition):
    return RuntimeEdge(source=source, target=target, condition=condition)


def test_compiled_langgraph_executes_nodes_by_edges_not_input_order():
    definition = workflow(
        nodes=[
            node("end-1", "END"),
            node("agent-1", "AGENT_REF", agentId=3),
            node("start-1", "START"),
        ],
        edges=[edge("start-1", "agent-1"), edge("agent-1", "end-1")],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"message": "hello"}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "agent-1", "end-1"]
    assert result["outputs"]["agent-1"]["sourceType"] == "AGENT"
    assert result["outputs"]["agent-1"]["sourceId"] == 3


def test_compiled_langgraph_supports_workflow_ref_nodes():
    definition = workflow(
        nodes=[node("start-1", "START"), node("workflow-1", "WORKFLOW_REF", workflowId=9), node("end-1", "END")],
        edges=[edge("start-1", "workflow-1"), edge("workflow-1", "end-1")],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "workflow-1", "end-1"]
    assert result["outputs"]["workflow-1"]["sourceType"] == "WORKFLOW"
    assert result["outputs"]["workflow-1"]["sourceId"] == 9


def test_rejects_cycles_before_compiling_langgraph():
    definition = workflow(
        nodes=[node("a"), node("b")],
        edges=[edge("a", "b"), edge("b", "a")],
    )

    with pytest.raises(ValueError, match="workflow graph contains a cycle"):
        compile_workflow_graph(definition)


def test_condition_node_routes_by_input_path_and_edge_condition():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node("condition-1", "CONDITION", inputPath="route"),
            node("agent-a", "AGENT_REF", agentId=1),
            node("agent-b", "AGENT_REF", agentId=2),
            node("end-1", "END"),
        ],
        edges=[
            edge("start-1", "condition-1"),
            conditional_edge("condition-1", "agent-a", "a"),
            conditional_edge("condition-1", "agent-b", "b"),
            edge("agent-a", "end-1"),
            edge("agent-b", "end-1"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"route": "b"}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "condition-1", "agent-b", "end-1"]
    assert "agent-a" not in result["outputs"]
    assert result["outputs"]["condition-1"]["selectedTarget"] == "agent-b"


def test_condition_node_uses_default_target_when_no_edge_condition_matches():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node("condition-1", "CONDITION", inputPath="route", defaultTarget="agent-b"),
            node("agent-a", "AGENT_REF", agentId=1),
            node("agent-b", "AGENT_REF", agentId=2),
        ],
        edges=[
            edge("start-1", "condition-1"),
            conditional_edge("condition-1", "agent-a", "a"),
            conditional_edge("condition-1", "agent-b", "b"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"route": "unknown"}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "condition-1", "agent-b"]
    assert result["outputs"]["condition-1"]["selectedTarget"] == "agent-b"


def test_condition_node_supports_numeric_comparison_expression():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node("condition-1", "CONDITION"),
            node("high-score", "AGENT_REF", agentId=1),
            node("low-score", "AGENT_REF", agentId=2),
        ],
        edges=[
            edge("start-1", "condition-1"),
            conditional_edge("condition-1", "high-score", "score >= 80"),
            conditional_edge("condition-1", "low-score", "score < 80"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"score": 91}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "condition-1", "high-score"]
    assert result["outputs"]["condition-1"]["selectedTarget"] == "high-score"


def test_condition_node_supports_contains_and_exists_expressions():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node("condition-1", "CONDITION"),
            node("vip-agent", "AGENT_REF", agentId=1),
            node("email-agent", "AGENT_REF", agentId=2),
            node("fallback", "AGENT_REF", agentId=3),
        ],
        edges=[
            edge("start-1", "condition-1"),
            conditional_edge("condition-1", "vip-agent", "tags contains vip"),
            conditional_edge("condition-1", "email-agent", "user.email exists"),
            conditional_edge("condition-1", "fallback", "fallback"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"tags": ["basic", "vip"], "user": {}}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "condition-1", "vip-agent"]
    assert result["outputs"]["condition-1"]["selectedTarget"] == "vip-agent"


def test_router_node_routes_by_configured_rules():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node(
                "router-1",
                "ROUTER",
                routes=[
                    {"name": "sales", "condition": "intent == sales", "target": "sales-agent"},
                    {"name": "support", "condition": "intent == support", "target": "support-agent"},
                ],
            ),
            node("sales-agent", "AGENT_REF", agentId=1),
            node("support-agent", "AGENT_REF", agentId=2),
        ],
        edges=[
            edge("start-1", "router-1"),
            edge("router-1", "sales-agent"),
            edge("router-1", "support-agent"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"intent": "support"}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "router-1", "support-agent"]
    assert "sales-agent" not in result["outputs"]
    assert result["outputs"]["router-1"]["selectedRoute"] == "support"
    assert result["outputs"]["router-1"]["selectedTarget"] == "support-agent"


def test_router_node_uses_default_target_when_no_rule_matches():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node(
                "router-1",
                "ROUTER",
                defaultTarget="fallback-agent",
                routes=[
                    {"name": "sales", "condition": "intent == sales", "target": "sales-agent"},
                ],
            ),
            node("sales-agent", "AGENT_REF", agentId=1),
            node("fallback-agent", "AGENT_REF", agentId=3),
        ],
        edges=[
            edge("start-1", "router-1"),
            edge("router-1", "sales-agent"),
            edge("router-1", "fallback-agent"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {"intent": "other"}, "visited": [], "outputs": {}})

    assert result["visited"] == ["start-1", "router-1", "fallback-agent"]
    assert result["outputs"]["router-1"]["selectedRoute"] == "default"
    assert result["outputs"]["router-1"]["selectedTarget"] == "fallback-agent"


def test_parallel_join_runs_all_branches_before_joining_outputs():
    definition = workflow(
        nodes=[
            node("start-1", "START"),
            node("parallel-1", "PARALLEL"),
            node("agent-a", "AGENT_REF", agentId=1),
            node("agent-b", "AGENT_REF", agentId=2),
            node("join-1", "JOIN"),
            node("end-1", "END"),
        ],
        edges=[
            edge("start-1", "parallel-1"),
            edge("parallel-1", "agent-a"),
            edge("parallel-1", "agent-b"),
            edge("agent-a", "join-1"),
            edge("agent-b", "join-1"),
            edge("join-1", "end-1"),
        ],
    )

    graph = compile_workflow_graph(definition)
    result = graph.invoke({"input": {}, "visited": [], "outputs": {}})

    assert result["visited"][0:2] == ["start-1", "parallel-1"]
    assert set(result["visited"][2:4]) == {"agent-a", "agent-b"}
    assert result["visited"][4:] == ["join-1", "end-1"]
    assert result["outputs"]["join-1"]["joinedNodeIds"] == ["agent-a", "agent-b"]
    assert set(result["outputs"]["join-1"]["joinedOutputs"].keys()) == {"agent-a", "agent-b"}
