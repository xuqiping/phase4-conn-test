package com.superprogrammer.system.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;

/**
 * 联网搜索运维配置回显（GET /api/system/settings/web-search）。
 *
 * - enabled/activeProvider/maxResults/timeoutMs：system_settings 直读。
 * - hasXxxKey：各外部供应商 key 是否已配置（AES 解密非空），**不回显明文**（仿 LLM provider key 范式）。
 * - builtinConfigured：自建 SearXNG 是否已部署（读 @Value search.searxng.base-url，只读，部署期配置）。
 * - providerAvailability：各 provider available() 实时自检（供前端展示当前实际可用项）。
 */
@Data
@Builder
public class WebSearchSettingsVO {
    private boolean enabled;
    private String activeProvider;
    private Integer maxResults;
    private Integer timeoutMs;
    private boolean hasTavilyKey;
    private boolean hasSerperKey;
    private boolean hasBingKey;
    private boolean builtinConfigured;
    /** provider 名 → available() 实时自检结果。 */
    private Map<String, Boolean> providerAvailability;
}
