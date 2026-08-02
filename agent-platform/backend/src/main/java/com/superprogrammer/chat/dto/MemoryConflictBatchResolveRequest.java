package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** 批量解决记忆冲突（统一 decision：KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD）。 */
@Data
public class MemoryConflictBatchResolveRequest {
    @NotNull
    private String decision;
}
