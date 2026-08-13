package com.superprogrammer.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationRunServiceTest {
    @Test
    void executesCasesPersistsMetricsAndCompletesRun() {
        RunRepository repository = new RunRepository();
        EvaluationRunService service = new EvaluationRunService(repository, Runnable::run,
                (kbId, question, operatorId) -> new EvaluationRunService.Outcome(
                        "trace-1", List.of("11", "99"), "SUPPORTED", true, true, false));

        EvaluationRunService.Run run = service.start(1L, 3L, "pipeline-v2", 7L);

        assertEquals("COMPLETED", repository.run.status());
        assertEquals(0.5d, repository.result.metrics().get("recall"));
        assertEquals(1d, repository.result.metrics().get("mrr"));
        assertEquals("trace-1", repository.result.traceId());
        assertEquals(run.id(), repository.run.id());
    }

    private static final class RunRepository implements EvaluationRunService.Repository {
        private EvaluationRunService.Run run;
        private EvaluationRunService.Result result;

        public EvaluationService.Dataset findDataset(long tenantId, long datasetId) {
            return new EvaluationService.Dataset(datasetId, tenantId, 9L, "回归集", null, 7L);
        }
        public List<EvaluationService.EvalCase> listCases(long tenantId, long datasetId) {
            return List.of(new EvaluationService.EvalCase(8L, datasetId, "FACT", "问题",
                    List.of("11", "12"), List.of(), true, Map.of()));
        }
        public EvaluationRunService.Run insertRun(EvaluationRunService.Run value) {
            run = value.withId(5L); return run;
        }
        public void updateRun(EvaluationRunService.Run value) { run = value; }
        public void insertResult(EvaluationRunService.Result value) { result = value; }
    }
}
