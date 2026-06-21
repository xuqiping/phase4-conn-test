package com.superprogrammer.chat.dto;

import lombok.Data;

/** 冲突候选单条（GET /memories/conflicts 返回内的 candidates 元素）。 */
@Data
public class MemoryCandidateVO {
    private Long id;            // 新事实未入库 → null
    private String memoryKey;
    private String memoryValue;
    private String category;
}
