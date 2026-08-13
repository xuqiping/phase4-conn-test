package com.superprogrammer.knowledge.evaluation;

import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RagEvaluationPipelineTest {
    @Test
    void evaluatesThroughRealRetrievalServiceAndKeepsTraceAndRankedEvidenceIds() {
        RagRetrievalService retrieval = mock(RagRetrievalService.class);
        when(retrieval.retrieve(any(), eq(7L))).thenReturn(RagRetrieveVO.builder()
                .traceId("trace-9").confidenceState("SUPPORTED").abstained(false)
                .evidenceL2(List.of(
                        RagRetrieveVO.EvidenceVO.builder().nodeId(11L).citationIndex(1).build(),
                        RagRetrieveVO.EvidenceVO.builder().nodeId(12L).citationIndex(2).build()))
                .citations(List.of(RagRetrieveVO.CitationVO.builder().nodeId(11L).build()))
                .build());

        EvaluationRunService.Outcome result = new RagEvaluationPipeline(retrieval).evaluate(9L, "问题", 7L);

        assertEquals("trace-9", result.traceId());
        assertEquals(List.of("11", "12"), result.rankedChunkIds());
        assertEquals("SUPPORTED", result.confidenceState());
    }
}
