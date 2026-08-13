package com.superprogrammer.knowledge.context;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EvidencePolicyServiceTest {
    @Test
    void appliesDynamicBudgetDiversityValidationTokenCapAndConfidence() {
        EvidencePolicyService service = new EvidencePolicyService(
                new CoverageSelector(), new ContextBuilder(), new com.superprogrammer.knowledge.answer.ConfidenceEvaluator(.8, .5));
        List<EvidencePolicyService.EvidenceItem> input = new ArrayList<>();
        for (long i = 1; i <= 12; i++) {
            input.add(new EvidencePolicyService.EvidenceItem(i, i <= 8 ? 1L : i, "内容" + i,
                    "h" + i, 1 - i * .01, true, true));
        }
        input.add(new EvidencePolicyService.EvidenceItem(99L, 99L, "越权", "x", .99, false, true));

        EvidencePolicyService.PolicyResult result = service.apply("PROCEDURE", 20, input, 1000, .85, false);

        assertEquals(10, result.evidence().size());
        assertFalse(result.evidence().stream().anyMatch(item -> item.nodeId() == 99L));
        assertTrue(result.evidence().stream().map(EvidencePolicyService.EvidenceItem::documentId).distinct().count() >= 4);
        assertEquals("SUPPORTED", result.confidenceState());
    }
}
