package com.superprogrammer.system.dto;

import lombok.Data;

@Data
public class LlmModelDefaultsUpdateRequest {
    private String chatModel;
    private String embeddingModel;
    /** 修复III C2（2x-2）：默认生图模型。 */
    private String imageModel;
    /** 全局默认视频模型（仿生图默认范式）。 */
    private String videoModel;
}
