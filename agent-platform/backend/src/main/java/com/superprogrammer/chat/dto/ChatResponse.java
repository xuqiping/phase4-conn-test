package com.superprogrammer.chat.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class ChatResponse {

    private Long sessionId;
    private Long messageId;
    private String content;
    private String mode;
    private String metadata;
}
