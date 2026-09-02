package com.superprogrammer.asset.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 修复XI C1：两级词汇 dto 双容错解析 / 扁平全集 / 入参反序列化
 * （读侧 {@link RoleVocab#parse} 与入参侧 {@link RoleVocabDeserializer} 单一事实源验证）。
 */
class RoleVocabTest {

    private static final List<RoleVocab> FALLBACK = List.of(new RoleVocab("通用", new ArrayList<>()));

    @Test
    void parse_legacyStringArray_eachBecomesChildlessLevelOne() {
        List<RoleVocab> out = RoleVocab.parse(new ObjectMapper(), "[\"人物\",\"道具\"]", FALLBACK);
        assertEquals(2, out.size());
        assertEquals("人物", out.get(0).getKey());
        assertTrue(out.get(0).getChildren().isEmpty());
        assertEquals("道具", out.get(1).getKey());
    }

    @Test
    void parse_objectArray_childrenPreserved_defaultsEmpty() {
        List<RoleVocab> out = RoleVocab.parse(new ObjectMapper(),
                "[{\"key\":\"人物\",\"children\":[\"老人\",\"孩童\"]},{\"key\":\"风格\"}]", FALLBACK);
        assertEquals(2, out.size());
        assertEquals(List.of("老人", "孩童"), out.get(0).getChildren());
        assertTrue(out.get(1).getChildren().isEmpty()); // children 缺省视为空
    }

    @Test
    void parse_mixedAndInvalidElements_skippedOrFallback() {
        // 混合数组：string + object + 数字/null（非法元素跳过，不炸整行）
        List<RoleVocab> mixed = RoleVocab.parse(new ObjectMapper(),
                "[\"人物\",{\"key\":\"道具\"},3,null]", FALLBACK);
        assertEquals(2, mixed.size());
        // 非数组 → 回落 fallback
        assertEquals(1, RoleVocab.parse(new ObjectMapper(), "{\"key\":\"人物\"}", FALLBACK).size());
        // 坏 JSON → 回落 fallback
        assertEquals(1, RoleVocab.parse(new ObjectMapper(), "[broken", FALLBACK).size());
        // 空白 → 回落 fallback
        assertEquals(1, RoleVocab.parse(new ObjectMapper(), "  ", FALLBACK).size());
    }

    @Test
    void flatten_parentsThenChildrenInOrder() {
        List<RoleVocab> vocab = RoleVocab.parse(new ObjectMapper(),
                "[{\"key\":\"人物\",\"children\":[\"老人\",\"孩童\"]},{\"key\":\"道具\",\"children\":[\"剑\"]}]", FALLBACK);
        assertEquals(List.of("人物", "老人", "孩童", "道具", "剑"), RoleVocab.flatten(vocab));
    }

    @Test
    void deserializer_acceptsLegacyAndTwoLevelPayloads() throws Exception {
        ObjectMapper om = new ObjectMapper();
        // 旧 payload（string 元素）——前端未升级期间平滑接入
        ProjectUpdateRequest legacy = om.readValue(
                "{\"narrativeRoles\":[\"人物\",\"通用\"]}", ProjectUpdateRequest.class);
        assertEquals(2, legacy.getNarrativeRoles().size());
        assertTrue(legacy.getNarrativeRoles().get(0).getChildren().isEmpty());

        // 新 payload（两级对象）
        ProjectUpdateRequest twoLevel = om.readValue(
                "{\"narrativeRoles\":[{\"key\":\"人物\",\"children\":[\"老人\"]}]}", ProjectUpdateRequest.class);
        assertEquals("人物", twoLevel.getNarrativeRoles().get(0).getKey());
        assertEquals(List.of("老人"), twoLevel.getNarrativeRoles().get(0).getChildren());
    }
}
