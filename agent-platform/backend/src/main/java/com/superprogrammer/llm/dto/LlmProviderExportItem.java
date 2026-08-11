package com.superprogrammer.llm.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

/**
 * 全局供应商导出/导入条目（问题 10x-2）。
 * <p>导出端点解密 apiKeyEnc → 此处 apiKey 为<b>明文</b>（仅 admin 可调导出，前端二次确认 + 审计留痕）。
 * 导入端点接收此 DTO，按 name upsert，apiKey 重新 AES 加密落库。
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown=true)}：导入旧文件/异构文件时忽略未知字段，防注入失败。
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public class LlmProviderExportItem {
    /** 唯一标识，导入 upsert 的匹配键。必填。 */
    private String name;
    private String displayName;
    /** OPENAI_COMPATIBLE / ANTHROPIC */
    private String protocol;
    /** 完整请求 URL（FR-001 全 URL 直发）。必填。 */
    private String apiEndpoint;
    /** 明文 API Key（导出含明文，导入重新加密；空则 upsert 时保留原 key）。 */
    private String apiKey;
    /** JSON 数组字符串，如 ["gpt-4o"] */
    private String models;
    /** 扩展配置 JSON */
    private String config;
    private Integer sortOrder;
    /** CHAT / EMBEDDING / VIDEO / IMAGE */
    private String category;
    /** ACTIVE / INACTIVE，导入时默认 ACTIVE */
    private String status;
}
