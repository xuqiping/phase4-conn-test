package com.superprogrammer.asset.dto;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;

import java.io.IOException;
import java.util.List;

/**
 * narrativeRoles 入参双容错（修复XI C1）：数组元素 string|object 同判——
 * 旧 payload {@code ["人物"]} 与新 payload {@code [{"key":"人物","children":["老人"]}]} 均可入，
 * 判型与读侧 {@link RoleVocab#fromArray} 单一事实源。
 */
public class RoleVocabDeserializer extends StdDeserializer<List<RoleVocab>> {

    @SuppressWarnings("unchecked")
    public RoleVocabDeserializer() {
        super((Class<List<RoleVocab>>) (Class<?>) List.class);
    }

    @Override
    public List<RoleVocab> deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
        JsonNode node = p.readValueAsTree();
        return RoleVocab.fromArray(node);
    }
}
