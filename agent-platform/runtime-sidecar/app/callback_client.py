from __future__ import annotations

import os
from typing import Any

import httpx
from pydantic import BaseModel, Field, field_validator

from app.logging_config import TRACEPARENT_HEADER, current_traceparent

# 安全审计 #1：sidecar→Java 回调共享密钥，与后端 runtime.callback.token (env RUNTIME_CALLBACK_TOKEN) 同值。
TOKEN_HEADER = "X-Runtime-Token"


def _callback_token() -> str | None:
    return os.environ.get("RUNTIME_CALLBACK_TOKEN")


class RuntimeNodeCallbackRequest(BaseModel):
    executionId: str
    rootExecutionId: str
    parentExecutionId: str | None = None
    nodeId: str
    sourceType: str
    sourceId: int
    userId: int | None = None
    input: dict[str, Any] = Field(default_factory=dict)
    traceId: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)


class RuntimeNodeCallbackResponse(BaseModel):
    success: bool
    selectedSkillIds: list[int] = Field(default_factory=list)
    stepOutputs: list[dict[str, Any]] = Field(default_factory=list)
    output: dict[str, Any] = Field(default_factory=dict)
    error: str | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)

    # Java 对 RETRIEVAL/AGENT 回调不带 selectedSkillIds/stepOutputs → 序列化为 null。
    # default_factory 仅在字段缺省时生效，null 仍会触发 list_type 校验失败 → EXECUTION_FAILED。
    # before-validator 把 null 视作空列表（SKILL 专用字段对其他 sourceType 无意义）。
    @field_validator("selectedSkillIds", "stepOutputs", mode="before")
    @classmethod
    def _empty_list_if_none(cls, v):
        return [] if v is None else v


def execute_runtime_callback(
    java_base_url: str,
    request: RuntimeNodeCallbackRequest,
    timeout: float = 120.0,
) -> RuntimeNodeCallbackResponse:
    url = f"{java_base_url.rstrip('/')}/api/runtime/callbacks/nodes/execute"
    headers: dict[str, str] = {}
    token = _callback_token()
    if token:
        headers[TOKEN_HEADER] = token
    # 日志系统 LOG-FR-08：回注 traceparent，Java 回调侧日志接入同一 trace（跨语言一条链）
    traceparent = current_traceparent()
    if traceparent:
        headers[TRACEPARENT_HEADER] = traceparent
    try:
        # trust_env=False：回调永远直连 Java，不读系统/环境代理（HTTP_PROXY/HTTPS_PROXY）。
        # 否则本机开了代理（如 Clash 127.0.0.1:7892）时，localhost 回调被代理拦截 → 502。
        with httpx.Client(timeout=timeout, trust_env=False, headers=headers) as client:
            response = client.post(url, json=request.model_dump(mode="json"))
        response.raise_for_status()
    except httpx.HTTPStatusError as exc:
        body = exc.response.text.strip()
        detail = f": {body}" if body else ""
        raise RuntimeError(f"Java runtime callback failed with status {exc.response.status_code}{detail}") from exc
    except httpx.TimeoutException as exc:
        raise RuntimeError(f"Java runtime callback timed out after {timeout}s") from exc
    except httpx.HTTPError as exc:
        raise RuntimeError(f"Java runtime callback request failed: {exc}") from exc
    envelope = response.json()
    return RuntimeNodeCallbackResponse.model_validate(envelope["data"])

