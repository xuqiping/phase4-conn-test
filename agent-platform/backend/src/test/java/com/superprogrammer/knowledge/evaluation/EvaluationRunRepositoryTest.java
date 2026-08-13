package com.superprogrammer.knowledge.evaluation;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

class EvaluationRunRepositoryTest {
    @Test
    void persistsRunStatusMetricsAndTraceableResult() {
        EvaluationMapper mapper = mock(EvaluationMapper.class);
        PostgresEvaluationRepository repository = new PostgresEvaluationRepository(mapper, new ObjectMapper());
        EvaluationRunService.Run run = new EvaluationRunService.Run(0, 1, 3, "v2", "QUEUED", 7,
                OffsetDateTime.now(), null, Map.of(), null);
        doAnswer(invocation -> { ((EvaluationMapper.RunRow) invocation.getArgument(0)).id = 5L; return null; })
                .when(mapper).insertRun(any());

        EvaluationRunService.Run saved = repository.insertRun(run);
        repository.updateRun(saved.withStatus("COMPLETED", OffsetDateTime.now(), Map.of("recall", 1d), null));
        repository.insertResult(new EvaluationRunService.Result(0, 5, 8, "trace-1", Map.of("mrr", 1d), "PASS"));

        assertEquals(5L, saved.id());
        verify(mapper).updateRun(argThat(row -> "COMPLETED".equals(row.status) && row.summaryMetrics.contains("recall")));
        verify(mapper).insertResult(argThat(row -> "trace-1".equals(row.traceId) && row.metrics.contains("mrr")));
    }
}
