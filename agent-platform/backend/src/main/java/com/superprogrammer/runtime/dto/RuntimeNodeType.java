package com.superprogrammer.runtime.dto;

public enum RuntimeNodeType {
    START,
    END,
    INPUT,
    SKILL,
    AGENT_REF,
    WORKFLOW_REF,
    ROUTER,
    CONDITION,
    PARALLEL,
    JOIN,
    HUMAN_APPROVAL,
    HUMAN_INPUT,
    TOOL_CALL,
    RETRIEVAL
}
