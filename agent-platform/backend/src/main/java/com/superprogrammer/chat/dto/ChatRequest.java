package com.superprogrammer.chat.dto;

import lombok.Data;

@Data
public class ChatRequest {

    private Long sessionId;
    private String message;
    private Long agentId;
    private Long workflowId;
    private String model;
}
