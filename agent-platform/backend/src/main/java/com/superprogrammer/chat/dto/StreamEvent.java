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
    /** 当前会话 ID（修 #3：流式建新会话后回读，避免每条消息新建会话）。前端在收到任意事件时读取并回填 currentSessionId。 */
    private Long sessionId;

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
