package com.superprogrammer.knowledge.migration;

import com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO;
import com.superprogrammer.knowledge.evaluation.EvaluationRunService;
import com.superprogrammer.knowledge.evaluation.PostgresEvaluationRepository;
import com.superprogrammer.knowledge.evaluation.ReleaseGateService;
import com.superprogrammer.knowledge.opensearch.KnowledgeIndexOperationsService;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

class RagRolloutReadinessServiceTest {
    @Test
    void derivesReleaseGateFromLatestCompletedPersistedEvaluationRun() {
        KnowledgeIndexOperationsService indexes = mock(KnowledgeIndexOperationsService.class);
        PostgresEvaluationRepository evaluations = mock(PostgresEvaluationRepository.class);
        when(indexes.status(9L)).thenReturn(new KnowledgeIndexStatusVO(9L, "READY", null, null,
                null, null, null, null, null, null, null));
        when(evaluations.findLatestCompletedRun(1L, 9L)).thenReturn(new EvaluationRunService.Run(
                5, 1, 3, "pipeline-v2", "COMPLETED", 7, OffsetDateTime.now(), OffsetDateTime.now(),
                Map.of("recall", .95, "citationCoverage", .98), null));
        RagRolloutReadinessService readiness = new RagRolloutReadinessService(indexes, evaluations,
                new ReleaseGateService(Map.of("recall", .92, "citationCoverage", .95)));
        readiness.recordReconciliation(9L, true);

        assertTrue(readiness.readiness(9L).releaseGatePassed());
    }
}
