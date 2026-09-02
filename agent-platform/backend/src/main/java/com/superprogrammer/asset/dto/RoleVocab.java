package com.superprogrammer.asset.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 叙事角色两级受控词汇条目（修复XI XI-3/C1）：一级桶 key + 子类 children（≤20，每名 ≤30 字）。
 *
 * <p>存储 shape：{@code asset_projects.narrative_roles} JSONB 数组 {@code [{"key":"人物","children":["老人"]}]}；
 * 存量一级 shape（string 元素数组）由 V169 迁移，读侧（{@link #parse}）与入参侧
 * （{@link RoleVocabDeserializer}）双容错同源判型——string 元素视为无子类一级。
 */
@Slf4j
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RoleVocab {

    private String key;
    private List<String> children = new ArrayList<>();

    /**
     * 双容错解析（读侧单一事实源）：数组元素 string → 一级无子类；object → {key,children}
     * （children 缺省/非数组视为空）；其余元素跳过；整体非数组/解析失败回落 fallback。
     */
    public static List<RoleVocab> parse(ObjectMapper om, String json, List<RoleVocab> fallback) {
        if (json == null || json.isBlank()) {
            return new ArrayList<>(fallback);
        }
        try {
            JsonNode node = om.readTree(json);
            if (!node.isArray()) {
                return new ArrayList<>(fallback);
            }
            return fromArray(node);
        } catch (Exception e) {
            log.warn("parse narrativeRoles failed, fallback default: {}", e.getMessage());
            return new ArrayList<>(fallback);
        }
    }

    /** JsonNode 数组 → 词汇列表（{@link #parse} 与 {@link RoleVocabDeserializer} 共用判型）。 */
    public static List<RoleVocab> fromArray(JsonNode node) {
        List<RoleVocab> out = new ArrayList<>();
        if (node == null || !node.isArray()) {
            return out;
        }
        for (JsonNode el : node) {
            if (el.isTextual()) {
                out.add(new RoleVocab(el.asText(), new ArrayList<>()));
            } else if (el.isObject()) {
                JsonNode key = el.get("key");
                if (key == null || !key.isTextual()) {
                    continue;
                }
                List<String> children = new ArrayList<>();
                JsonNode ch = el.get("children");
                if (ch != null && ch.isArray()) {
                    for (JsonNode c : ch) {
                        if (c.isTextual()) {
                            children.add(c.asText());
                        }
                    }
                }
                out.add(new RoleVocab(key.asText(), children));
            }
        }
        return out;
    }

    /** 扁平全集（父 + 全部子类，保序不去重）：syncRoleLinks 受控校验 / 复制过滤 / 筛选展开共用。 */
    public static List<String> flatten(List<RoleVocab> roles) {
        List<String> out = new ArrayList<>();
        if (roles == null) {
            return out;
        }
        for (RoleVocab r : roles) {
            if (r == null || r.getKey() == null) {
                continue;
            }
            out.add(r.getKey());
            if (r.getChildren() == null) {
                continue;
            }
            for (String c : r.getChildren()) {
                if (c != null) {
                    out.add(c);
                }
            }
        }
        return out;
    }
}
