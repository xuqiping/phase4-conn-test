package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

/** RAG/记忆模式全局开关视图（V26）。 */
@Data
@Builder
public class RagMemorySettingsVO {
    private Boolean enabled;
}
