import json
import asyncio

import structlog
from fastapi import FastAPI
from sse_starlette.sse import EventSourceResponse

from app.checkpoint_store import create_checkpoint_store
from app.logging_config import TRACEPARENT_HEADER, configure_logging, parse_traceparent
from app.models import ExecutionRequest, ExecutionResult
from app.runtime_executor import build_events, iter_events

# 日志系统 LOG-FR-08：structlog JSON 输出，与 Java 侧 LogstashEncoder 字段对齐
configure_logging()
log = structlog.get_logger("sidecar")

app = FastAPI(title="Agent Platform Runtime Sidecar")
checkpoint_store = create_checkpoint_store()
END_OF_EVENTS = object()


@app.middleware("http")
async def trace_context_middleware(request, call_next):
    """解析 Java 侧注入的 traceparent → trace_id/span_id 绑 contextvars（跨语言一条链）。
    缺失时自生成并打 trace_parent_missing 标记；每请求一行摘要（不记 body，PII 红线）。"""
    structlog.contextvars.clear_contextvars()
    structlog.contextvars.bind_contextvars(**parse_traceparent(request.headers.get(TRACEPARENT_HEADER)))
    response = await call_next(request)
    log.info(
        "sidecar request",
        method=request.method,
        path=request.url.path,
        status=response.status_code,
    )
    return response


@app.get("/health")
def health():
    return {"status": "UP", "service": "runtime-sidecar"}


@app.post("/api/runtime/executions")
def execute(request: ExecutionRequest):
    if request.runtime.get("stream", True):
        return EventSourceResponse(stream_events(iter_events(request, checkpoint_store)))
    events = build_events(request, checkpoint_store)
    return ExecutionResult(
        executionId=request.executionId,
        rootExecutionId=request.rootExecutionId,
        status=events[-1].status if events else "SUCCESS",
        events=events,
    )


async def stream_events(events):
    iterator = iter(events)
    while True:
        event = await asyncio.to_thread(next_event, iterator)
        if event is END_OF_EVENTS:
            return
        yield {"event": event.type, "data": json.dumps(event.model_dump(mode="json"))}


def next_event(iterator):
    try:
        return next(iterator)
    except StopIteration:
        return END_OF_EVENTS
