package com.superprogrammer.knowledge.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** 管理员默认或知识库覆盖的重排配置写入请求。 */
@Data
public class RankingConfigUpdateRequest {
    @NotBlank
    private String rankingMode;
    private String model;
    @Min(1) @Max(200)
    private Integer candidateLimit;
    @Min(1) @Max(200)
    private Integer finalLimit;
    @Min(1) @Max(50)
    private Integer batchSize;
    @Min(100) @Max(120000)
    private Integer timeoutMs;
    private String fallbackPolicy;
    private Boolean highAccuracyEnabled;
}

