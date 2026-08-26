package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class LlmModelDefaultsVO {
    private String chatModel;
    private String embeddingModel;
    /** 修复III C2（2x-2）：默认生图模型。 */
    private String imageModel;
}
