package com.superprogrammer.knowledge.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class RagRetrieveVO {

    private String traceId;
    private boolean abstained;
    /** abstain 时的话术/原因（SUPPORTED 时为 null）*/
    private String abstainReason;
    private String answer;
    private List<CitationVO> citations;
    private List<RecallHitVO> candidatesL0;
    private List<EvidenceVO> evidenceL2;
    private TokenBudgetVO tokenBudget;
    private long latencyMs;

    @Data
    @Builder
    public static class CitationVO {
        private int index;
        private Long documentId;
        private String title;
        private Long nodeId;
    }

    @Data
    @Builder
    public static class RecallHitVO {
        private Long nodeId;
        private Long documentId;
        private String title;
        private double cosineDistance;
        private double cosineSimilarity;
    }

    @Data
    @Builder
    public static class EvidenceVO {
        private Long nodeId;
        private Long documentId;
        private String title;
        private String content;
        private String contentHash;
        private String docType;
        private int citationIndex;
        private double rerankScore;
    }

    @Data
    @Builder
    public static class TokenBudgetVO {
        private int maxContextTokens;
        private int modelMaxContext;
        private int answerTokenReserve;
        private int effectiveContextCap;
        private int promptTokens;
    }
}
