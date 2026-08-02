from __future__ import annotations

from app.models import RuntimeNode


def resolve_source(node: RuntimeNode) -> tuple[str | None, int | None]:
    node_type = node.type.upper()
    if node_type == "AGENT_REF":
        return "AGENT", as_int(node.config.get("agentId"))
    if node_type == "WORKFLOW_REF":
        return "WORKFLOW", as_int(node.config.get("workflowId"))
    if node_type == "SKILL":
        return "SKILL", as_int(node.config.get("skillId"))
    if node_type == "RETRIEVAL":
        # v6 §2.4 检索节点：source_id = kbId（或 kbIds 首个，作 fallback）；实际 kbIds 由 nodeConfig 携带
        kb_id = as_int(node.config.get("kbId"))
        if kb_id is None:
            kb_ids = node.config.get("kbIds") or []
            if kb_ids:
                kb_id = as_int(kb_ids[0])
        return "RETRIEVAL", kb_id
    return None, None


def as_int(value) -> int | None:
    if value is None:
        return None
    return int(value)
