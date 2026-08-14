import hashlib
import hmac

import httpx

from app.callback_client import (
    SIGNATURE_HEADER,
    TIMESTAMP_HEADER,
    TOKEN_HEADER,
    RuntimeNodeCallbackRequest,
    RuntimeNodeCallbackResponse,
    build_callback_headers,
    execute_runtime_callback,
)


def test_build_callback_headers_signs_ts_and_body():
    """安全体系 S5 · SEC-FR-061（F2）：签名 = HMAC-SHA256(token, f"{ts}.{body}") hex。

    独立复算对拍：用返回头里的 ts 重算签名，与头内签名一致 = 签名契约自洽。
    """
    payload = '{"executionId":"1001","sourceType":"SKILL"}'
    headers = build_callback_headers("shared-secret", payload)

    assert headers[TOKEN_HEADER] == "shared-secret"
    ts = headers[TIMESTAMP_HEADER]
    assert ts.isdigit() and abs(int(ts) - int(__import__("time").time() * 1000)) < 60_000
    expected = hmac.new(
        "shared-secret".encode("utf-8"),
        f"{ts}.{payload}".encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()
    assert headers[SIGNATURE_HEADER] == expected


def test_build_callback_headers_empty_token_returns_no_auth():
    assert build_callback_headers(None, "{}") == {}
    assert build_callback_headers("", "{}") == {}


def test_callback_payload_matches_java_contract():
    request = RuntimeNodeCallbackRequest(
        executionId="1001",
        rootExecutionId="1001",
        nodeId="skill-1",
        sourceType="SKILL",
        sourceId=12,
        userId=1,
        input={"message": "hello"},
        traceId="trace-1",
        metadata={"externalThreadId": "sidecar-thread-1001"},
    )
    response = RuntimeNodeCallbackResponse(
        success=True,
        selectedSkillIds=[12],
        stepOutputs=[{"stepId": 1, "output": "done"}],
        output={"text": "done"},
        metadata={"traceId": "trace-1"},
    )

    assert request.model_dump(mode="json")["sourceType"] == "SKILL"
    assert request.model_dump(mode="json")["input"]["message"] == "hello"
    assert response.model_dump(mode="json")["selectedSkillIds"] == [12]
    assert response.model_dump(mode="json")["output"]["text"] == "done"


def test_execute_runtime_callback_reads_java_response_envelope(monkeypatch):
    captured = {}

    def fake_post(url, json, timeout):
        captured["url"] = url
        captured["json"] = json
        captured["timeout"] = timeout
        return httpx.Response(
            200,
            request=httpx.Request("POST", url),
            json={
                "code": 200,
                "message": "success",
                "data": {
                    "success": True,
                    "selectedSkillIds": [12],
                    "stepOutputs": [{"stepId": 1, "output": "done"}],
                    "output": {"text": "done"},
                    "metadata": {"traceId": "trace-1"},
                },
            },
        )

    monkeypatch.setattr(httpx, "post", fake_post)

    response = execute_runtime_callback(
        "http://java:8080/",
        RuntimeNodeCallbackRequest(
            executionId="1001",
            rootExecutionId="1001",
            nodeId="skill-1",
            sourceType="SKILL",
            sourceId=12,
        ),
        timeout=5,
    )

    assert captured["url"] == "http://java:8080/api/runtime/callbacks/nodes/execute"
    assert captured["json"]["sourceId"] == 12
    assert captured["timeout"] == 5
    assert response.success is True
    assert response.output == {"text": "done"}


def test_execute_runtime_callback_wraps_non_success_status(monkeypatch):
    def fake_post(url, json, timeout):
        return httpx.Response(
            500,
            request=httpx.Request("POST", url),
            text="callback exploded",
        )

    monkeypatch.setattr(httpx, "post", fake_post)

    try:
        execute_runtime_callback(
            "http://java:8080",
            RuntimeNodeCallbackRequest(
                executionId="1001",
                rootExecutionId="1001",
                nodeId="skill-1",
                sourceType="SKILL",
                sourceId=12,
            ),
        )
    except RuntimeError as exc:
        assert "Java runtime callback failed with status 500" in str(exc)
        assert "callback exploded" in str(exc)
    else:
        raise AssertionError("expected RuntimeError")


def test_execute_runtime_callback_wraps_timeout(monkeypatch):
    def fake_post(url, json, timeout):
        raise httpx.TimeoutException("timed out", request=httpx.Request("POST", url))

    monkeypatch.setattr(httpx, "post", fake_post)

    try:
        execute_runtime_callback(
            "http://java:8080",
            RuntimeNodeCallbackRequest(
                executionId="1001",
                rootExecutionId="1001",
                nodeId="skill-1",
                sourceType="SKILL",
                sourceId=12,
            ),
            timeout=1,
        )
    except RuntimeError as exc:
        assert "Java runtime callback timed out" in str(exc)
    else:
        raise AssertionError("expected RuntimeError")
