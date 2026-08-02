package com.superprogrammer.chat.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** PUT /memories/conflicts/{id}/resolve 请求体。
 *  decision ∈ KEEP_NEW/KEEP_OLD/KEEP_BOTH/DISCARD/KEEP_CUSTOM(M2 自定义合并)。
 *  customValue:仅 KEEP_CUSTOM 必填(用户手改后的最终 value)。 */
@Data
public class MemoryConflictResolveRequest {
    @NotBlank
    private String decision;
    /** M2:KEEP_CUSTOM 时用户手改的 value(前端默认填旧 value)。其它 decision 忽略。 */
    private String customValue;
}
