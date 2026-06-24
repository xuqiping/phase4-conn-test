package com.superprogrammer.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeNodeCallbackResponse {

    private boolean success;
    private List<Long> selectedSkillIds;
    private List<Map<String, Object>> stepOutputs;
    private Map<String, Object> output;
    private String error;
    private Map<String, Object> metadata;
}
