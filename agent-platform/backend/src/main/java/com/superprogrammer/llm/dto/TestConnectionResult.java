package com.superprogrammer.llm.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestConnectionResult {
    private boolean success;
    private String message;
    private String model;
    private Long durationMs;

    public static TestConnectionResult success(String model, long durationMs) {
        return TestConnectionResult.builder()
                .success(true)
                .message("连接成功")
                .model(model)
                .durationMs(durationMs)
                .build();
    }

    public static TestConnectionResult fail(String error) {
        return TestConnectionResult.builder()
                .success(false)
                .message("连接失败: " + error)
                .build();
    }
}
