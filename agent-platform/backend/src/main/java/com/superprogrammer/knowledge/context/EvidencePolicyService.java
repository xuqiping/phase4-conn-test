package com.superprogrammer.knowledge.context;

import com.superprogrammer.knowledge.answer.ConfidenceEvaluator;
import com.superprogrammer.knowledge.config.RagRecallProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Applies the evidence budget, final validation, diversity and confidence policy
 * after retrieval candidates have passed the authoritative PostgreSQL recheck.
 */
@Service
public class EvidencePolicyService {
    private final CoverageSelector coverageSelector;
    private final ContextBuilder contextBuilder;
    private final ConfidenceEvaluator confidenceEvaluator;

    @Autowired
    public EvidencePolicyService(RagRecallProperties recallProperties) {
        this(new CoverageSelector(), new ContextBuilder(), new ConfidenceEvaluator(
                recallProperties.getAbstain().getSoft(),
                recallProperties.getAbstain().getHard()));
    }

    public EvidencePolicyService(CoverageSelector coverageSelector,
                                 ContextBuilder contextBuilder,
                                 ConfidenceEvaluator confidenceEvaluator) {
        this.coverageSelector = coverageSelector;
        this.contextBuilder = contextBuilder;
        this.confidenceEvaluator = confidenceEvaluator;
    }

    public PolicyResult apply(String queryType,
                              int requestedEvidence,
                              List<EvidenceItem> input,
                              int tokenCap,
                              double confidenceScore,
                              boolean conflict) {
        List<ContextBuilder.Item> validated = contextBuilder.build(input.stream()
                .map(item -> new ContextBuilder.Item(item.nodeId(), item.content(), item.contentHash(),
                        item.authorized(), item.currentHash()))
                .toList(), tokenCap);

        Map<Long, EvidenceItem> byNodeId = new LinkedHashMap<>();
        input.forEach(item -> byNodeId.putIfAbsent(item.nodeId(), item));
        List<CoverageSelector.Candidate> candidates = validated.stream()
                .map(item -> byNodeId.get(item.nodeId()))
                .map(item -> new CoverageSelector.Candidate(item.nodeId(), item.documentId(), item.score()))
                .toList();
        int budget = coverageSelector.budget(queryType, requestedEvidence);
        List<EvidenceItem> selected = coverageSelector.select(candidates, budget).stream()
                .map(candidate -> byNodeId.get(candidate.nodeId()))
                .toList();
        String state = confidenceEvaluator.evaluate(confidenceScore, !selected.isEmpty(), conflict);
        return new PolicyResult(selected, state);
    }

    public record EvidenceItem(Long nodeId, Long documentId, String content, String contentHash,
                               double score, boolean authorized, boolean currentHash) {
    }

    public record PolicyResult(List<EvidenceItem> evidence, String confidenceState) {
        public PolicyResult {
            evidence = List.copyOf(evidence);
        }
    }
}
