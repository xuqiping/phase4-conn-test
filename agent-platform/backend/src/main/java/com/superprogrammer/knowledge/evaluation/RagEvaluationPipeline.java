package com.superprogrammer.knowledge.evaluation;

import com.superprogrammer.knowledge.dto.RagRetrieveRequest;
import com.superprogrammer.knowledge.dto.RagRetrieveVO;
import com.superprogrammer.knowledge.service.RagRetrievalService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;

/** Production evaluation adapter: runs the same retrieval chain used by /retrieve. */
@Component
@RequiredArgsConstructor
public class RagEvaluationPipeline implements EvaluationRunService.Pipeline {
    private final RagRetrievalService retrievalService;

    @Override
    public EvaluationRunService.Outcome evaluate(long kbId, String question, long operatorId) {
        RagRetrieveRequest request = new RagRetrieveRequest();
        request.setKbId(kbId);
        request.setQuery(question);
        request.setMode("PRECISION");
        request.setGenerateAnswer(true);
        request.setAdminHint(true);
        RagRetrieveVO result = retrievalService.retrieve(request, operatorId);
        List<String> ranked = result.getEvidenceL2() == null ? List.of() : result.getEvidenceL2().stream()
                .sorted(Comparator.comparingInt(RagRetrieveVO.EvidenceVO::getCitationIndex))
                .map(value -> String.valueOf(value.getNodeId())).toList();
        boolean citationValid = result.getCitations() != null && !result.getCitations().isEmpty();
        boolean faithful = !result.isAbstained() && citationValid && "SUPPORTED".equals(result.getConfidenceState());
        return new EvaluationRunService.Outcome(result.getTraceId(), ranked, result.getConfidenceState(),
                citationValid, faithful, result.isAbstained());
    }
}
