package com.superprogrammer.llm.dto;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class AvailableModelVO {
    private String modelId;
    private String displayName;
    private String providerName;
    private String source;
    /** 是否为管理员配置的该类型默认模型。 */
    private Boolean defaultModel;
    /**
     * 思考强度可用档位（修复IX-1）：ANTHROPIC 协议=三档全（协议原生）；
     * OpenAI 兼容系按 provider config thinking 声明（toggle→[OFF,STANDARD] / effort→三档）。
     * null=不支持思考选择（前端不显示选择器，现状布局）。
     */
    private java.util.List<String> thinkingLevels;
}
