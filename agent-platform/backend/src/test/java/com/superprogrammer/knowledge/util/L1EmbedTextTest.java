package com.superprogrammer.knowledge.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.superprogrammer.knowledge.service.internal.L1Metadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * L1EmbedText：拼接语义 + hash 稳定性 + 解析失败回退（Phase3，writer/worker/tx 三处共用，防漂移）。
 */
class L1EmbedTextTest {

    private final ObjectMapper om = new ObjectMapper();

    @Test
    void build_joinsAllNonBlankParts() {
        L1Metadata l1 = L1Metadata.builder()
                .summary("安装部署指南")
                .outline(List.of("环境准备", "数据库配置"))
                .importantRules(List.of("  ", "生产环境需备份"))
                .build();
        assertEquals("安装部署指南；环境准备；数据库配置；生产环境需备份", L1EmbedText.build(l1));
    }

    @Test
    void build_allBlank_returnsEmpty() {
        assertEquals("", L1EmbedText.build(L1Metadata.builder().build()));
        assertEquals("", L1EmbedText.build(null));
    }

    @Test
    void hashOfJson_stableForSameInput() {
        String json = "{\"summary\":\"如何安装\",\"outline\":[\"步骤一\"],\"importantRules\":[]}";
        assertEquals(L1EmbedText.hashOfJson(json, om), L1EmbedText.hashOfJson(json, om));
        assertNotEquals("", L1EmbedText.hashOfJson(json, om));
    }

    @Test
    void hashOfJson_blankInput_stableFallback() {
        assertEquals(L1EmbedText.hashOfJson(null, om), L1EmbedText.hashOfJson(null, om));
        assertEquals(L1EmbedText.hashOfJson("  ", om), L1EmbedText.hashOfJson("  ", om));
    }

    @Test
    void hashOfJson_invalidJson_fallsBackToRawHash() {
        String bad = "not json {";
        assertEquals(HashUtil.sha256(bad), L1EmbedText.hashOfJson(bad, om));
    }
}
