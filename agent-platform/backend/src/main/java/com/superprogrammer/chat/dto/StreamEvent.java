package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {
    private String type;    // CHUNK | THINKING | CITATION | DONE | ERROR
    private String content;

    public static StreamEvent chunk(String content) {
        return StreamEvent.builder().type("CHUNK").content(content).build();
    }
    public static StreamEvent thinking(String content) {
        return StreamEvent.builder().type("THINKING").content(content).build();
    }
    /** 阶段5：引用（JSON 串，结构同 RagRetrieveVO.CitationVO 列表），须在 DONE 前发。 */
    public static StreamEvent citation(String citationsJson) {
        return StreamEvent.builder().type("CITATION").content(citationsJson).build();
    }
    public static StreamEvent done() {
        return StreamEvent.builder().type("DONE").build();
    }
    public static StreamEvent error(String message) {
        return StreamEvent.builder().type("ERROR").content(message).build();
    }
}
