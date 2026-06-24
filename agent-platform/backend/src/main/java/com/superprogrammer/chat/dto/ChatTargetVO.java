package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatTargetVO {

    private String type;

    private String targetKey;

    private Long id;

    private String name;

    private String description;

    private Boolean available;

    private String disabledReason;

    private Map<String, Object> metadata;
}
