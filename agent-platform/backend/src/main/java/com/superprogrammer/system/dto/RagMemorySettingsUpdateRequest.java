package com.superprogrammer.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** RAG/记忆模式全局开关更新（V26）。 */
@Data
public class RagMemorySettingsUpdateRequest {
    @NotNull
    private Boolean enabled;
}
