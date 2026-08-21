package com.superprogrammer.projectgroup.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.common.exception.BusinessException;
import com.superprogrammer.common.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * 成员功能开关（allowed_kinds，17x#2，V139）编解码与判定。
 * <p>语义：NULL=不限；JSON 数组=白名单（如 ["CHAT","IMAGE"]）；空数组 []=全禁（仍在组不可消耗）。
 * 元素白名单= {@link ProjectGroupVisibilityService#OUTPUT_KINDS}（CHAT/EMBED/RERANK/IMAGE/VIDEO）。
 * 读侧宽容：坏 JSON 按不限回落（同组级可见性覆盖策略）；写侧 {@link #validate} 非法 400。
 */
@Slf4j
public final class MemberAllowedKinds {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private MemberAllowedKinds() {
    }

    /** 解析 allowed_kinds JSON 字符串；null/空/坏 JSON → null（不限）。 */
    public static List<String> parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            JsonNode arr = MAPPER.readTree(json);
            if (!arr.isArray()) {
                return null;
            }
            List<String> kinds = new ArrayList<>();
            for (JsonNode n : arr) {
                if (n.isTextual() && ProjectGroupVisibilityService.OUTPUT_KINDS.contains(n.asText())) {
                    kinds.add(n.asText());
                }
            }
            return kinds;
        } catch (Exception e) {
            log.warn("成员 allowed_kinds JSON 解析失败按不限回落: {}", json);
            return null;
        }
    }

    /** 序列化：null→null（不限）；空 list→"[]"（全禁，语义区别于 null）。 */
    public static String toJson(List<String> kinds) {
        if (kinds == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(kinds);
        } catch (Exception e) {
            throw new BusinessException(ErrorCode.BAD_REQUEST, "模块清单序列化失败");
        }
    }

    /** 写侧白名单校验：null 放行；元素须∈5 模块，否则 400。 */
    public static void validate(List<String> kinds) {
        if (kinds == null) {
            return;
        }
        for (String k : kinds) {
            if (k == null || !ProjectGroupVisibilityService.OUTPUT_KINDS.contains(k)) {
                throw new BusinessException(ErrorCode.BAD_REQUEST,
                        "非法模块标识（仅支持 CHAT/EMBED/RERANK/IMAGE/VIDEO）: " + k);
            }
        }
    }

    /**
     * 判定：白名单是否允许该 kind。
     *
     * @param json 成员行 allowed_kinds
     * @param kind 本次消耗模块；null 或非 5 模块（GROUP/MEDIA 等结算类 refType）不约束
     */
    public static boolean isAllowed(String json, String kind) {
        if (kind == null || !ProjectGroupVisibilityService.OUTPUT_KINDS.contains(kind)) {
            return true;
        }
        List<String> kinds = parse(json);
        if (kinds == null) {
            return true;                    // 不限
        }
        return kinds.contains(kind);        // 白名单（含 []=全禁）
    }
}
