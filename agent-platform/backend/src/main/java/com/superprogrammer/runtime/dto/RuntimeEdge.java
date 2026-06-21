package com.superprogrammer.runtime.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RuntimeEdge {

    private String source;
    private String target;
    private String sourceHandle;
    private String targetHandle;
    private String label;
    private String condition;
}
