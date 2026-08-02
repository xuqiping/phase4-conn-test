# Runtime Sidecar

Python FastAPI runtime sidecar for Plan 8 stage 3.

## Run

```bash
cd agent-platform/runtime-sidecar
python -m pip install -r requirements.txt
python -m uvicorn app.main:app --host 0.0.0.0 --port 8090
```

## Configuration

### Java runtime gateway

Java uses `runtime.gateway.mode=mock` by default. Switch to the real sidecar only for integration runs:

```yaml
runtime:
  gateway:
    mode: sidecar
    sidecar-base-url: http://localhost:8090
    java-callback-base-url: http://localhost:8080
```

Equivalent environment variables:

```bash
RUNTIME_GATEWAY_MODE=sidecar
RUNTIME_SIDECAR_BASE_URL=http://localhost:8090
RUNTIME_JAVA_CALLBACK_BASE_URL=http://localhost:8080
```

- `runtime.gateway.mode=mock`: Java uses `MockRuntimeGateway`; no Python process is required.
- `runtime.gateway.mode=sidecar`: Java calls `POST /api/runtime/executions` on this FastAPI service.
- `runtime.gateway.java-callback-base-url`: Java includes this value in `ExecutionRequest.runtime.javaCallbackBaseUrl`; sidecar uses it to call Java `POST /api/runtime/callbacks/nodes/execute` for `SKILL` and `AGENT_REF` nodes.

### Sidecar checkpoint store

By default, checkpoints are stored under `.runtime-checkpoints` in the sidecar working directory. Override with:

```bash
RUNTIME_CHECKPOINT_DIR=E:/runtime-checkpoints
```

## Test

```bash
cd agent-platform/runtime-sidecar
python -m pytest -q
```

## API

- `GET /health`
- `POST /api/runtime/executions`

`POST /api/runtime/executions` accepts the Java `ExecutionRequest` contract. When `runtime.stream` is true or omitted, it returns `text/event-stream`; when `runtime.stream=false`, it returns a JSON `ExecutionResult`.

## Current Scope

This stage implements the real HTTP sidecar boundary and compiles `WorkflowDefinition` into a LangGraph `StateGraph`. It emits standard `ExecutionEvent` records for `START`, `END`, `SKILL`, `AGENT_REF`, `WORKFLOW_REF`, `CONDITION`, rule-based `ROUTER`, `LLM_ROUTER`, `PARALLEL`, `JOIN`, and `HUMAN_APPROVAL` nodes.

When `runtime.javaCallbackBaseUrl` is present, `SKILL` and `AGENT_REF` nodes are executed through Java runtime callbacks. If callback execution fails, sidecar returns an `EXECUTION_FAILED` event with `failedNodeId`, `errorMessage`, and, when checkpointing is enabled, `recoveryCheckpointRef`.

`CONDITION` routing reads `node.config.inputPath` from request input and selects the outgoing edge whose `condition` value matches. `node.config.defaultTarget` is used when no condition matches.

Condition edges also support a constrained expression format without `eval`:

- `field == value`
- `field != value`
- `field > number`
- `field >= number`
- `field < number`
- `field <= number`
- `field contains value`
- `field exists`

Minimal `PARALLEL` / `JOIN` support fans out to multiple branch nodes and waits for all incoming branch nodes before `JOIN` runs. `JOIN` output contains `joinedNodeIds` and `joinedOutputs`.

Branch timeout, concurrency limits, and resource-aware parallelism remain later-stage work.

Rule-based `ROUTER` nodes use `node.config.routes`:

```json
[
  {"name": "sales", "condition": "intent == sales", "target": "sales-agent"},
  {"name": "support", "condition": "intent == support", "target": "support-agent"}
]
```

`node.config.defaultTarget` is used when no route rule matches.
