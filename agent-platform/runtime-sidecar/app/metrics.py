"""运维系统 OPS-FR-08：sidecar 图执行自定义指标（prometheus_client）。

高基数红线（与 Java 侧 BizMetrics 同规约）：executionId/traceId/userId 永远不进 label，
只允许有界枚举：result(SUCCESS/FAILED/WAITING_APPROVAL/WAITING_INPUT/CANCEL)、node_type(节点类型枚举)。

指标：
- sidecar_graph_executions_total{result}     图执行终态次数
- sidecar_graph_execution_duration_seconds   图执行耗时直方图（bucket 对齐 LLM 慢调用量级）
- sidecar_node_failures_total{node_type}     节点失败次数（按节点类型）
"""
import time
from typing import Optional

from prometheus_client import Counter, Histogram

graph_executions = Counter(
    "sidecar_graph_executions_total",
    "图执行终态次数（result=SUCCESS/FAILED/WAITING_*/CANCEL）",
    ["result"],
)
graph_duration = Histogram(
    "sidecar_graph_execution_duration_seconds",
    "图执行耗时",
    buckets=(0.1, 0.5, 1, 2, 5, 10, 30, 60, 120, 300),
)
node_failures = Counter(
    "sidecar_node_failures_total",
    "节点失败次数（按节点类型，低基数）",
    ["node_type"],
)

_TERMINAL_RESULTS = {"SUCCESS", "FAILED", "WAITING_APPROVAL", "WAITING_INPUT"}


def _record(request, last_event, elapsed: float, result_override: Optional[str] = None):
    """按终末事件落指标。任何异常吞掉——指标绝不阻断执行链路。"""
    try:
        result = result_override or (last_event.status if last_event else "SUCCESS")
        if result not in _TERMINAL_RESULTS and result != "CANCEL":
            result = "FAILED" if "FAIL" in str(result).upper() else "SUCCESS"
        graph_executions.labels(result=result).inc()
        graph_duration.observe(max(elapsed, 0.0))
        node_id = getattr(last_event, "nodeId", None) if last_event else None
        if result == "FAILED" and node_id:
            nodes_by_id = {n.id: n for n in request.workflow.nodes}
            node = nodes_by_id.get(node_id)
            node_failures.labels(node_type=(node.type.upper() if node else "unknown")).inc()
    except Exception:  # noqa: BLE001 - 指标埋点静默兜底
        pass


def record_events(request, events: list, start: float):
    """非流式路径：执行完按末事件落指标。"""
    _record(request, events[-1] if events else None, time.monotonic() - start)


def observe(events_iter, request):
    """流式路径：生成器包装——透传事件不改动，自然穷尽按末事件落指标；
    客户端断连（GeneratorExit）记 CANCEL。"""
    start = time.monotonic()
    last = None
    try:
        for ev in events_iter:
            last = ev
            yield ev
        _record(request, last, time.monotonic() - start)
    except GeneratorExit:
        _record(request, last, time.monotonic() - start, result_override="CANCEL")
        raise
