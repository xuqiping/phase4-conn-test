package com.superprogrammer.llm.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Set;

/**
 * OpenAI 兼容系的思考参数声明（修复IX-1 A3，Q3 拍板 provider 配置声明制）。
 *
 * <p>来源：llm_providers.config（jsonb）的 {@code thinking} 节，形状：
 * <pre>{@code
 * {"thinking": {"style": "toggle"|"effort", "models": ["可选：仅这些 modelId 启用；缺省=该 provider 全部模型"]}}
 * }</pre>
 * <ul>
 *   <li><b>toggle</b> 风格：智谱 GLM-4.5+/火山 doubao-seed/K2 的 OpenAI 兼容端形状——
 *       OFF→{@code thinking:{"type":"disabled"}}、STANDARD/DEEP→{@code {"type":"enabled"}}（协议无深度态）。</li>
 *   <li><b>effort</b> 风格：OpenAI o 系/gpt-5 形状——OFF/STANDARD/DEEP→{@code reasoning_effort:"low"/"medium"/"high"}。</li>
 * </ul>
 * <b>未声明（null）= 一个思考参数都不发（现状），UI 不显示选择器。</b>
 * 声明内容真伪由运维对照各家文档自行负责（如 glm-5.1/5.3 实测忽略 thinking——发了无害但无效，见 9x 遗留观察）。
 *
 * <p>解析容错：坏 JSON/非法 style → warn 一行 + 返回 null（不炸 provider 创建与模型列表接口）。
 * 构造时解析一次随 provider 缓存（providers reload 自然重建），非每请求解析。
 */
@Slf4j
public record ThinkingSpec(Style style, Set<String> models) {

    public enum Style { TOGGLE, EFFORT }

    /** 该 modelId 是否吃到本声明（models 缺省=全部；有值=白名单）。 */
    public boolean appliesTo(String modelId) {
        return models == null || models.isEmpty() || models.contains(modelId);
    }

    /**
     * 该 modelId 可下发的档位集合（模型列表接口用）：
     * TOGGLE→[OFF,STANDARD]（无深度态，诚实下发）；EFFORT→三档。
     * 不适用（未声明/不在白名单）→ null。
     */
    public List<String> levelsFor(String modelId) {
        if (!appliesTo(modelId)) {
            return null;
        }
        return style == Style.TOGGLE
                ? List.of(ThinkingLevel.OFF.name(), ThinkingLevel.STANDARD.name())
                : List.of(ThinkingLevel.OFF.name(), ThinkingLevel.STANDARD.name(), ThinkingLevel.DEEP.name());
    }

    /** 解析 config jsonb 的 thinking 节；无/坏/非法 → null（现状）。 */
    public static ThinkingSpec parse(ObjectMapper om, String configJson) {
        if (configJson == null || configJson.isBlank()) {
            return null;
        }
        try {
            var node = om.readTree(configJson).path("thinking");
            if (!node.isObject() || node.isEmpty()) {
                return null;
            }
            String styleStr = node.path("style").asText("").trim().toLowerCase();
            Style style = switch (styleStr) {
                case "toggle" -> Style.TOGGLE;
                case "effort" -> Style.EFFORT;
                default -> null;
            };
            if (style == null) {
                log.warn("thinking 声明 style 非法（仅支持 toggle/effort），按未声明处理: {}", styleStr);
                return null;
            }
            Set<String> models = null;
            var modelsNode = node.path("models");
            if (modelsNode.isArray() && !modelsNode.isEmpty()) {
                Set<String> allowlist = new java.util.LinkedHashSet<>();
                for (var m : modelsNode) {
                    allowlist.add(m.asText());
                }
                models = allowlist;
            }
            return new ThinkingSpec(style, models);
        } catch (Exception e) {
            log.warn("解析 thinking 声明失败（按未声明处理）: {}", e.getMessage());
            return null;
        }
    }
}
