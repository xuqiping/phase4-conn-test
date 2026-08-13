package com.superprogrammer.knowledge.answer;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GroundedAnswerServiceTest {
    @Test
    void batchesEvidenceKeepsAllowedCitationsAndDetectsConflict() {
        GroundedAnswerService service = new GroundedAnswerService();
        List<GroundedAnswerService.Evidence> evidence = List.of(
                new GroundedAnswerService.Evidence(1, "退款期限为 7 天"),
                new GroundedAnswerService.Evidence(2, "退款期限为 15 天"),
                new GroundedAnswerService.Evidence(3, "需要提供订单号"));
        List<Integer> batchSizes = new ArrayList<>();

        GroundedAnswerService.Result result = service.synthesize(evidence, 2, batch -> {
            batchSizes.add(batch.size());
            if (batch.get(0).citationId() == 1) {
                return List.of(
                        new GroundedAnswerService.Fact("退款期限", "7 天", List.of(1, 99)),
                        new GroundedAnswerService.Fact("退款期限", "15 天", List.of(2)));
            }
            return List.of(new GroundedAnswerService.Fact("申请材料", "订单号", List.of(3)));
        });

        assertEquals(List.of(2, 1), batchSizes);
        assertEquals(Set.of(1, 2, 3), result.facts().stream()
                .flatMap(f -> f.citationIds().stream()).collect(java.util.stream.Collectors.toSet()));
        assertTrue(result.conflict());
    }
}
