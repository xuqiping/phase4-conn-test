package com.superprogrammer.media.dto;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.Data;

import java.util.Map;

/** Provider 发送前一次性准备的实际 body 与由同一 body 派生的脱敏快照。 */
@Data
@Builder
public class PreparedMediaRequest {
    private Map<String, Object> body;
    private JsonNode snapshot;
}
