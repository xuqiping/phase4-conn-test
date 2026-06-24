from __future__ import annotations

from datetime import datetime, timezone
from typing import Any

from pydantic import BaseModel, Field, field_validator


class RuntimeNode(BaseModel):
    id: str
    type: str
    label: str | None = None
    config: dict[str, Any] = Field(default_factory=dict)


class RuntimeEdge(BaseModel):
    source: str
    target: str
    sourceHandle: str | None = None
    targetHandle: str | None = None
    label: str | None = None
    condition: str | None = None


class WorkflowDefinition(BaseModel):
    version: str | None = None
    workflowId: int | None = None
    name: str | None = None
    nodes: list[RuntimeNode]
    edges: list[RuntimeEdge] = Field(default_factory=list)

    @field_validator("nodes")
    @classmethod
    def nodes_must_not_be_empty(cls, value: list[RuntimeNode]) -> list[RuntimeNode]:
        if not value:
            raise ValueError("workflow.nodes must not be empty")
        return value


class ExecutionRequest(BaseModel):
    executionId: str
    rootExecutionId: str
    parentExecutionId: str | None = None
    userId: int | None = None
    sourceType: str | None = None
    sourceId: int | None = None
    workflow: WorkflowDefinition
    input: dict[str, Any] = Field(default_factory=dict)
    runtime: dict[str, Any] = Field(default_factory=dict)


class ExecutionEvent(BaseModel):
    executionId: str
    rootExecutionId: str
    parentExecutionId: str | None = None
    nodeId: str | None = None
    type: str
    status: str
    sourceType: str | None = None
    sourceId: int | None = None
    input: dict[str, Any] | None = None
    output: dict[str, Any] | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    timestamp: datetime = Field(default_factory=lambda: datetime.now(timezone.utc))


class ExecutionResult(BaseModel):
    executionId: str
    rootExecutionId: str
    status: str
    events: list[ExecutionEvent]
