package com.superprogrammer.chat.dto;

import lombok.Data;

import java.util.List;

@Data
public class ChatRequest {

    private Long sessionId;
    private String message;
    private Long agentId;
    private Long workflowId;
    private String model;
    /** CHAT 模式检索 scope（阶段5 RAG）。 */
    private List<Long> kbIds;
    /** 记忆模式开关（V26，非 null 时持久化到会话；null=不改）。 */
    private Boolean ragEnabled;

    /** 联网搜索开关（CHAT 模式，非 null 时持久化到会话；null=不改继承）。ON→LLM 生成前联网检索注入。 */
    private Boolean webSearchEnabled;
}
