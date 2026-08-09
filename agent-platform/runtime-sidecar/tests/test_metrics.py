"""运维系统 OPS-FR-08：sidecar 自定义指标测试。

全局 registry 计数随测试累计，一律用"前后差值"断言，不依赖绝对值。
"""
import json

import pytest
from sse_starlette.sse import AppStatus

from app import metrics as sidecar_metrics
from app.models import ExecutionEvent, ExecutionRequest
# 红线：必须复用同一 TestClient——sse-starlette AppStatus.should_exit_event 是全局 anyio.Event，
# 第二个 TestClient 的 portal 跑在不同 event loop 上会炸 "bound to a different event loop"
from tests.test_runtime_api import client, sample_request


@pytest.fixture(autouse=True)
def reset_sse_app_status():
    """sse-starlette 2.x 的 AppStatus.should_exit_event 全局只建一次且绑定首个 event loop，
    不重置会导致后续任何流式测试炸 'bound to a different event loop'（套件级隐性炸弹）。"""
    yield
    AppStatus.should_exit_event = None


def _metric_value(metric, labels):
    """prometheus_client Counter 子样本当前值（无样本视为 0）。"""
    from prometheus_client import REGISTRY
    value = REGISTRY.get_sample_value(metric, labels)
    return value or 0.0


def _to_model(request_dict):
    return ExecutionRequest.model_validate(request_dict)


# ---- 1. 非流式成功 → SUCCESS 计数 +1、耗时直方图 +1 ----

def test_non_stream_success_increments_success_counter():
    before = _metric_value("sidecar_graph_executions_total", {"result": "SUCCESS"})
    duration_before = _metric_value("sidecar_graph_execution_duration_seconds_count", {})

    response = client.post("/api/runtime/executions", json=sample_request(stream=False))

    assert response.status_code == 200
    assert _metric_value("sidecar_graph_executions_total", {"result": "SUCCESS"}) == before + 1
    assert _metric_value("sidecar_graph_execution_duration_seconds_count", {}) == duration_before + 1


# ---- 2. 节点失败 → FAILED 计数 +1、node_failures 按 node_type=AGENT_REF +1 ----

def test_failed_node_increments_failure_and_node_type_counters():
    request = sample_request(stream=False)
    request["workflow"]["nodes"] = [
        {"id": "start-1", "type": "START", "label": "Start", "config": {}},
        {"id": "agent-1", "type": "AGENT_REF", "label": "Broken", "config": {"agentId": 1, "fail": True}},
    ]
    request["workflow"]["edges"] = [{"source": "start-1", "target": "agent-1"}]
    fail_before = _metric_value("sidecar_graph_executions_total", {"result": "FAILED"})
    node_before = _metric_value("sidecar_node_failures_total", {"node_type": "AGENT_REF"})

    response = client.post("/api/runtime/executions", json=request)

    assert response.status_code == 200
    assert response.json()["status"] == "FAILED"
    assert _metric_value("sidecar_graph_executions_total", {"result": "FAILED"}) == fail_before + 1
    assert _metric_value("sidecar_node_failures_total", {"node_type": "AGENT_REF"}) == node_before + 1


# ---- 3. 流式跑完 → SUCCESS 计数 +1（生成器穷尽路径）----

def test_stream_full_consumption_increments_success_counter():
    before = _metric_value("sidecar_graph_executions_total", {"result": "SUCCESS"})

    with client.stream("POST", "/api/runtime/executions", json=sample_request(stream=True)) as response:
        assert response.status_code == 200
        lines = [line for line in response.iter_lines() if line.startswith("data: ")]

    assert json.loads(lines[-1].removeprefix("data: "))["type"] == "EXECUTION_COMPLETED"
    assert _metric_value("sidecar_graph_executions_total", {"result": "SUCCESS"}) == before + 1


# ---- 4. 生成器提前关闭（客户端断连）→ CANCEL 计数 +1 ----

def test_observe_generator_close_records_cancel():
    cancel_before = _metric_value("sidecar_graph_executions_total", {"result": "CANCEL"})
    request = _to_model(sample_request(stream=True))

    def events():
        yield ExecutionEvent(executionId="1", rootExecutionId="1", type="EXECUTION_STARTED", status="RUNNING")
        yield ExecutionEvent(executionId="1", rootExecutionId="1", type="EXECUTION_COMPLETED", status="SUCCESS")

    observed = sidecar_metrics.observe(events(), request)
    next(observed)          # 消费一个事件后丢弃 → close 触发 GeneratorExit
    observed.close()

    assert _metric_value("sidecar_graph_executions_total", {"result": "CANCEL"}) == cancel_before + 1


# ---- 5. /metrics 端点暴露且含自定义指标族；label 无高基数值（红线反向断言）----

def test_metrics_endpoint_exposes_custom_families_without_high_cardinality_labels():
    client.post("/api/runtime/executions", json=sample_request(stream=False))

    response = client.get("/metrics")

    assert response.status_code == 200
    text = response.text
    assert "sidecar_graph_executions_total" in text
    assert "sidecar_graph_execution_duration_seconds_bucket" in text
    assert "sidecar_node_failures_total" in text
    # 高基数红线：traceId/executionId/userId 绝不出现在任何指标行
    custom_lines = [line for line in text.splitlines() if line.startswith("sidecar_")]
    assert custom_lines, "应至少有一行 sidecar_ 自定义指标"
    for line in custom_lines:
        assert "trace-1001" not in line
        assert "1001" not in line.split("{")[0]
        for forbidden in ("traceId", "executionId", "userId", "trace_id"):
            assert forbidden not in line
