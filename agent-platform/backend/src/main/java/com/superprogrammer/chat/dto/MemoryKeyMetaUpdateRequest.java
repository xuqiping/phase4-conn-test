package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** M2:panel 手改 per-key 时序标请求体。 */
@Data
public class MemoryKeyMetaUpdateRequest {
    @NotNull
    private Boolean isTemporal;
}
