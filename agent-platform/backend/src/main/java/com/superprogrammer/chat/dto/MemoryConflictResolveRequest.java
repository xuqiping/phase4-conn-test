package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** PUT /memories/conflicts/{id}/resolve 请求体。decision ∈ KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD。 */
@Data
public class MemoryConflictResolveRequest {
    @NotBlank
    private String decision;
}
