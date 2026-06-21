package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.Set;

/**
 * retrieveEvidence 返回（阶段5）：证据上下文 + 注入编号 + 引用元数据，供 Chat/Agent 注入、post-gen Citation 校验、/ask CITATION 事件。
 * 不含 LLM 答案——生成交给调用方（聊天带历史 / /ask 流式 / 检索节点回下游）。
 */
@Data
@Builder
public class EvidenceResult {

    /** 证据上下文（"[n] 标注来源" 前缀 + 截断证据块），注入 chat SYSTEM msg / agent systemPrompt。 */
    private String systemPrompt;
    /** 合法引用编号集（post-gen CitationChecker 校验用，A1）。 */
    private Set<Integer> injectedIndexes;
    /** 引用元数据（index/documentId/title/nodeId），供 /ask CITATION 事件 + chat metadata。 */
    private List<RagRetrieveVO.CitationVO> citations;
    private boolean abstained;
    private String abstainReason;
    private String answer;
    private String traceId;

    public static EvidenceResult abstain(String traceId, String reason, String answer) {
        return EvidenceResult.builder()
                .abstained(true)
                .abstainReason(reason)
                .answer(answer)
                .traceId(traceId)
                .build();
    }
}
