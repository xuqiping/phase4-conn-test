package com.superprogrammer.system.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** RAG 召回 query 扩展全局开关更新。 */
@Data
public class RagRecallSettingsUpdateRequest {
    @NotNull
    private Boolean enabled;
    /** 可选：切块触发阈值字数（输入>此值切块；≤此值改写）。缺省/非法(<1) → 不改。默认 200。 */
    private Integer threshold;
}
