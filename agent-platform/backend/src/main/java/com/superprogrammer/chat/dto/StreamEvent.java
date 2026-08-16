package com.superprogrammer.chat.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class StreamEvent {
    private String type;    // CHUNK | THINKING | CITATION | FILE_CARDS | DONE | ERROR | INPUT_REQUIRED | INCLUSION_CONFIRM
    private String content;
    /** 当前会话 ID（修 #3：流式建新会话后回读，避免每条消息新建会话）。前端在收到任意事件时读取并回填 currentSessionId。 */
    private Long sessionId;
    /** INPUT_REQUIRED 等结构化事件载荷：{executionId,nodeId,inputKey,question,inputType,options,...} */
    private Map<String, Object> data;

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
    public static StreamEvent ragState(String state) {
        return StreamEvent.builder().type("RAG_STATE").content(state).build();
    }
    /** 二期 P3（FR-203）：召回命中的文件记忆卡片（RecalledFileCard 列表 JSON 串），DONE 前发，前端渲染文件卡片。 */
    public static StreamEvent fileCards(String fileCardsJson) {
        return StreamEvent.builder().type("FILE_CARDS").content(fileCardsJson).build();
    }
    public static StreamEvent done() {
        return StreamEvent.builder().type("DONE").build();
    }
    public static StreamEvent error(String message) {
        return StreamEvent.builder().type("ERROR").content(message).build();
    }
    /** 工作流命中 HUMAN_INPUT：把待答问题规格透给前端。 */
    public static StreamEvent inputRequired(Long sessionId, Map<String, Object> payload) {
        return StreamEvent.builder().type("INPUT_REQUIRED").sessionId(sessionId).data(payload).build();
    }

    /** 5x #7 收录确认：content=inclusionConfirm 载荷 JSON（messageId/status/hits），DONE 前发，
     *  前端捕获后并入消息 metadata 渲染「需要回答/不用了」按钮。 */
    public static StreamEvent inclusionConfirm(String payloadJson) {
        return StreamEvent.builder().type("INCLUSION_CONFIRM").content(payloadJson).build();
    }
}
