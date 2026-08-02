package com.superprogrammer.system.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 联网搜索运维配置写入（PUT /api/system/settings/web-search）。所有字段可选（null=不改）。
 *
 * - enabled：全局总开关。
 * - activeProvider：tavily/serper/bing/builtin（白名单校验，非法→builtin）。
 * - maxResults：1~10。
 * - timeoutMs：≥1000。
 * - tavilyKey/serperKey/bingKey：明文写入，后端 AES 加密存 system_settings；null=不改，空串=清除。
 *
 * 权限：{@code @RequirePermission("role:manage")}（同其他 system_settings 端点；plan 写 system:config，
 * 沿用既有 role:manage 保持一致）。
 */
@Data
public class WebSearchSettingsUpdateRequest {

    private Boolean enabled;
    private String activeProvider;

    @Min(1)
    @Max(10)
    private Integer maxResults;

    @Min(1000)
    private Integer timeoutMs;

    /** 明文 key（AES 加密存）；null=不改，""=清除。回显永不返回明文。 */
    private String tavilyKey;
    private String serperKey;
    private String bingKey;
}
