"""日志系统 LOG-FR-08：traceparent 解析 / contextvars 绑定 / 回调回注 单测。"""
import json

import structlog
from fastapi.testclient import TestClient

from app.logging_config import current_traceparent, parse_traceparent
from app.main import app

client = TestClient(app)

VALID_TP = "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"


def test_parse_valid_traceparent():
    ctx = parse_traceparent(VALID_TP)
    assert ctx == {
        "trace_id": "4bf92f3577b34da6a3ce929d0e0e4736",
        "span_id": "00f067aa0ba902b7",
    }


def test_parse_missing_or_invalid_generates_and_marks():
    for bad in (None, "", "garbage", "00-short-00-01"):
        ctx = parse_traceparent(bad)
        assert len(ctx["trace_id"]) == 32
        assert len(ctx["span_id"]) == 16
        assert ctx["trace_parent_missing"] is True


def test_current_traceparent_roundtrip():
    structlog.contextvars.clear_contextvars()
    assert current_traceparent() is None
    structlog.contextvars.bind_contextvars(**parse_traceparent(VALID_TP))
    assert current_traceparent() == VALID_TP
    structlog.contextvars.clear_contextvars()


def test_request_log_line_carries_trace_id(capsys):
    response = client.get("/health", headers={"traceparent": VALID_TP})
    assert response.status_code == 200
    lines = [l for l in capsys.readouterr().out.strip().splitlines() if l.strip()]
    sidecar_lines = [json.loads(l) for l in lines if '"sidecar request"' in l]
    assert sidecar_lines, "应有一行 sidecar request 摘要"
    assert sidecar_lines[-1]["trace_id"] == "4bf92f3577b34da6a3ce929d0e0e4736"
    assert sidecar_lines[-1]["path"] == "/health"


def test_request_without_traceparent_marked_missing(capsys):
    response = client.get("/health")
    assert response.status_code == 200
    lines = [l for l in capsys.readouterr().out.strip().splitlines() if l.strip()]
    sidecar_lines = [json.loads(l) for l in lines if '"sidecar request"' in l]
    assert sidecar_lines[-1]["trace_parent_missing"] is True
    assert len(sidecar_lines[-1]["trace_id"]) == 32
