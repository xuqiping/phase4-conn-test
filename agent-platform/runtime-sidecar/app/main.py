import json
import asyncio

from fastapi import FastAPI
from sse_starlette.sse import EventSourceResponse

from app.checkpoint_store import create_checkpoint_store
from app.models import ExecutionRequest, ExecutionResult
from app.runtime_executor import build_events, iter_events

app = FastAPI(title="Agent Platform Runtime Sidecar")
checkpoint_store = create_checkpoint_store()
END_OF_EVENTS = object()


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
