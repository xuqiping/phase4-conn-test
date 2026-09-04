package com.superprogrammer.knowledge.evaluation;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class EvaluationRunServiceTest {
    @Test
    void executesCasesPersistsMetricsAndCompletesRun() {
        RunRepository repository = new RunRepository(1);
        EvaluationRunService service = new EvaluationRunService(repository, Runnable::run,
                (kbId, question, operatorId) -> new EvaluationRunService.Outcome(
                        "trace-1", List.of("11", "99"), "SUPPORTED", true, true, false));

        EvaluationRunService.Run run = service.start(1L, 3L, "pipeline-v2", 7L);

        assertEquals("COMPLETED", repository.run.status());
        assertEquals(0.5d, repository.results.get(0).metrics().get("recall"));
        assertEquals(1d, repository.results.get(0).metrics().get("mrr"));
        assertEquals("trace-1", repository.results.get(0).traceId());
        assertEquals(run.id(), repository.run.id());
        assertEquals(0d, repository.run.summaryMetrics().get("errorCount"));
    }

    @Test
    void singleCaseFailureMarksErrorAndRunStillCompletes() {
        RunRepository repository = new RunRepository(2);
        AtomicInteger calls = new AtomicInteger();
        // 第 1 例正常，第 2 例模拟事实提炼/网关故障抛业务异常
        EvaluationRunService service = new EvaluationRunService(repository, Runnable::run,
                (kbId, question, operatorId) -> {
                    if (calls.incrementAndGet() == 2) {
                        throw new IllegalStateException("事实提炼模型未返回合法 JSON");
                    }
                    return new EvaluationRunService.Outcome(
                            "trace-ok", List.of("11"), "SUPPORTED", true, true, false);
                });

        service.start(1L, 3L, "pipeline-v2", 7L);

        assertEquals("COMPLETED", repository.run.status());
        assertEquals(2, repository.results.size());
        assertEquals("PASS", repository.results.get(0).verdict());
        assertEquals("ERROR", repository.results.get(1).verdict());
        assertEquals(1d, repository.run.summaryMetrics().get("errorCount"));
        // 出错用例按 0 分计入均值：第 1 例 recall=0.5（2 期望中 1 命中）、第 2 例 0 分 → 均值 0.25
        assertEquals(0.25d, repository.run.summaryMetrics().get("recall"));
    }

    private static final class RunRepository implements EvaluationRunService.Repository {
        private EvaluationRunService.Run run;
        private final List<EvaluationRunService.Result> results = new ArrayList<>();
        private final int caseCount;

        RunRepository(int caseCount) { this.caseCount = caseCount; }

        public EvaluationService.Dataset findDataset(long tenantId, long datasetId) {
            return new EvaluationService.Dataset(datasetId, tenantId, 9L, "回归集", null, 7L);
        }
        public List<EvaluationService.EvalCase> listCases(long tenantId, long datasetId) {
            List<EvaluationService.EvalCase> cases = new ArrayList<>();
            for (long i = 0; i < caseCount; i++) {
                cases.add(new EvaluationService.EvalCase(8L + i, datasetId, "FACT", "问题" + i,
                        List.of("11", "12"), List.of(), true, Map.of()));
            }
            return cases;
        }
        public EvaluationRunService.Run insertRun(EvaluationRunService.Run value) {
            run = value.withId(5L); return run;
        }
        public EvaluationRunService.Run findRun(long tenantId, long runId) { return run; }
        public void updateRun(EvaluationRunService.Run value) { run = value; }
        public void insertResult(EvaluationRunService.Result value) { results.add(value); }
    }
}
