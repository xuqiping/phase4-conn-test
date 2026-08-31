package com.superprogrammer.llm.dto;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/** 修复IX-1 A3：thinking 声明解析与档位下发。 */
class ThinkingSpecTest {

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void parse_nullOrBlankConfig_shouldReturnNull() {
        assertNull(ThinkingSpec.parse(mapper, null));
        assertNull(ThinkingSpec.parse(mapper, "  "));
    }

    @Test
    void parse_configWithoutThinkingNode_shouldReturnNull() {
        assertNull(ThinkingSpec.parse(mapper, "{\"capabilities\":{\"m1\":{}}}"));
    }

    @Test
    void parse_badJsonOrIllegalStyle_shouldReturnNull() {
        assertNull(ThinkingSpec.parse(mapper, "{not-json"));
        assertNull(ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"magic\"}}"));
        assertNull(ThinkingSpec.parse(mapper, "{\"thinking\":{}}"));
    }

    @Test
    void parse_toggleAndEffort_shouldCarryStyleAndModels() {
        ThinkingSpec toggle = ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"toggle\"}}");
        assertNotNull(toggle);
        assertEquals(ThinkingSpec.Style.TOGGLE, toggle.style());
        assertNull(toggle.models());

        ThinkingSpec effort = ThinkingSpec.parse(mapper,
                "{\"thinking\":{\"style\":\"effort\",\"models\":[\"glm-5.1\",\"glm-5.3\"]}}");
        assertNotNull(effort);
        assertEquals(ThinkingSpec.Style.EFFORT, effort.style());
        assertEquals(Set.of("glm-5.1", "glm-5.3"), effort.models());
    }

    @Test
    void levelsFor_toggleTwoLevels_effortThree_nullWhenFiltered() {
        ThinkingSpec toggle = ThinkingSpec.parse(mapper, "{\"thinking\":{\"style\":\"toggle\"}}");
        assertEquals(List.of("OFF", "STANDARD"), toggle.levelsFor("any-model"));

        ThinkingSpec effort = ThinkingSpec.parse(mapper,
                "{\"thinking\":{\"style\":\"effort\",\"models\":[\"glm-5.3\"]}}");
        assertEquals(List.of("OFF", "STANDARD", "DEEP"), effort.levelsFor("glm-5.3"));
        assertNull(effort.levelsFor("glm-5.1"), "白名单外模型应得 null");
    }
}
