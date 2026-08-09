"""sidecar 结构化日志（日志系统 LOG-FR-08）：structlog JSON 输出 + W3C traceparent 对齐。

与 Java 侧约定（跨语言一条链）：
- Java 调 sidecar 时由 micrometer tracing 自动注入 `traceparent` 请求头（WebClient 插桩）；
- 本模块中间件解析 traceparent → trace_id/span_id 绑进 structlog contextvars，
  本请求内全部日志行自动带 trace_id，与 Java 日志同号可串查；
- 缺失/非法 traceparent → 自生成 trace_id 并打 `trace_parent_missing=True` 标记（降级不断链）；
- sidecar 回调 Java 时经 `current_traceparent()` 回注同一 trace（见 callback_client）。

JSON 字段对齐 Java 侧 LogstashEncoder：timestamp/level/logger/message + trace_id/span_id。
安全红线同 Java：日志不落用户输入/LLM 响应原文，只落 length/id。
"""
from __future__ import annotations

import logging
import sys
import uuid

import structlog

TRACEPARENT_HEADER = "traceparent"


def configure_logging() -> None:
    """structlog JSON + stdlib logging 桥接（uvicorn/fastapi 日志同 JSON 格式）。进程启动时调一次。"""
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.processors.add_log_level,
            structlog.processors.StackInfoRenderer(),
            structlog.processors.format_exc_info,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(ensure_ascii=False),
        ],
        wrapper_class=structlog.make_filtering_bound_logger(logging.INFO),
        logger_factory=structlog.PrintLoggerFactory(sys.stdout),
        cache_logger_on_first_use=True,
    )
    # stdlib（uvicorn.access 等）也走 JSON，字段经 ProcessorFormatter 统一
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(
        structlog.stdlib.ProcessorFormatter(
            processors=[
                structlog.stdlib.ProcessorFormatter.remove_processors_meta,
                structlog.processors.TimeStamper(fmt="iso"),
                structlog.processors.JSONRenderer(ensure_ascii=False),
            ],
            foreign_pre_chain=[structlog.contextvars.merge_contextvars, structlog.processors.add_log_level],
        )
    )
    root = logging.getLogger()
    root.handlers = [handler]
    root.setLevel(logging.INFO)


def parse_traceparent(header: str | None) -> dict:
    """解析 W3C traceparent（version-traceid-spanid-flags）。无效/缺失 → 自生成 + 缺失标记。"""
    if header:
        parts = header.strip().split("-")
        if len(parts) == 4 and len(parts[1]) == 32 and len(parts[2]) == 16:
            return {"trace_id": parts[1], "span_id": parts[2]}
    return {
        "trace_id": uuid.uuid4().hex,
        "span_id": uuid.uuid4().hex[:16],
        "trace_parent_missing": True,
    }


def current_traceparent() -> str | None:
    """从 contextvars 取当前 trace 组装 traceparent（sidecar 回调 Java 时回注同一链）。"""
    ctx = structlog.contextvars.get_contextvars()
    trace_id = ctx.get("trace_id")
    span_id = ctx.get("span_id")
    if trace_id and span_id:
        return f"00-{trace_id}-{span_id}-01"
    return None
