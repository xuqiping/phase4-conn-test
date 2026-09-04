package com.superprogrammer.knowledge.evaluation;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;

/** Executes immutable evaluation cases and persists per-case traceable metrics. */
public class EvaluationRunService {
    private final Repository repository;
    private final Executor executor;
    private final Pipeline pipeline;
    private final RagMetricsCalculator calculator = new RagMetricsCalculator();

    public EvaluationRunService(Repository repository, Executor executor, Pipeline pipeline) {
        this.repository = repository;
        this.executor = executor;
        this.pipeline = pipeline;
    }

    public Run start(long tenantId, long datasetId, String pipelineVersion, long operatorId) {
        EvaluationService.Dataset dataset = repository.findDataset(tenantId, datasetId);
        if (dataset == null) throw new IllegalArgumentException("evaluation dataset not found");
        if (pipelineVersion == null || pipelineVersion.isBlank()) {
            throw new IllegalArgumentException("pipeline version required");
        }
        Run run = repository.insertRun(new Run(0L, tenantId, datasetId, pipelineVersion.trim(),
                "QUEUED", operatorId, OffsetDateTime.now(), null, Map.of(), null));
        executor.execute(() -> execute(run, dataset));
        return run;
    }

    public Run get(long tenantId, long runId) {
        Run run = repository.findRun(tenantId, runId);
        if (run == null) throw new IllegalArgumentException("evaluation run not found");
        return run;
    }

    private void execute(Run queued, EvaluationService.Dataset dataset) {
        Run running = queued.withStatus("RUNNING", null, Map.of(), null);
        repository.updateRun(running);
        try {
            List<EvaluationService.EvalCase> cases = repository.listCases(queued.tenantId(), queued.datasetId());
            double recall = 0, mrr = 0, ndcg = 0;
            int supported = 0, citationValid = 0, faithful = 0, correctAbstention = 0, errors = 0;
            for (EvaluationService.EvalCase value : cases) {
                Outcome outcome;
                try {
                    outcome = pipeline.evaluate(dataset.kbId(), value.question(), queued.startedBy());
                } catch (RuntimeException error) {
                    // 单用例故障不废整跑批（LLM 偶发非法输出/网关抖动）：记 ERROR 结果继续，
                    // 指标均值按 0 分计入该用例，errorCount 汇总暴露故障规模。
                    repository.insertResult(new Result(0L, queued.id(), value.id(), null, Map.of(),
                            "ERROR"));
                    errors++;
                    continue;
                }
                RagMetricsCalculator.Metrics metrics = calculator.calculate(outcome.rankedChunkIds(),
                        new LinkedHashSet<>(value.expectedChunkIds()), 10);
                Map<String, Double> values = new LinkedHashMap<>();
                values.put("recall", metrics.recall());
                values.put("mrr", metrics.mrr());
                values.put("ndcg", metrics.ndcg());
                values.put("citationCoverage", outcome.citationValid() ? 1d : 0d);
                values.put("faithfulness", outcome.faithful() ? 1d : 0d);
                boolean abstentionCorrect = value.answerable() != outcome.abstained();
                values.put("abstentionAccuracy", abstentionCorrect ? 1d : 0d);
                repository.insertResult(new Result(0L, queued.id(), value.id(), outcome.traceId(), values,
                        verdict(value, outcome, metrics)));
                recall += metrics.recall(); mrr += metrics.mrr(); ndcg += metrics.ndcg();
                supported += "SUPPORTED".equals(outcome.confidenceState()) ? 1 : 0;
                citationValid += outcome.citationValid() ? 1 : 0;
                faithful += outcome.faithful() ? 1 : 0;
                correctAbstention += abstentionCorrect ? 1 : 0;
            }
            int count = cases.size();
            Map<String, Double> summary = new LinkedHashMap<>();
            summary.put("caseCount", (double) count);
            summary.put("errorCount", (double) errors);
            summary.put("recall", average(recall, count));
            summary.put("mrr", average(mrr, count));
            summary.put("ndcg", average(ndcg, count));
            summary.put("supportedRate", average(supported, count));
            summary.put("citationCoverage", average(citationValid, count));
            summary.put("faithfulness", average(faithful, count));
            summary.put("abstentionAccuracy", average(correctAbstention, count));
            repository.updateRun(running.withStatus("COMPLETED", OffsetDateTime.now(), summary, null));
        } catch (RuntimeException error) {
            repository.updateRun(running.withStatus("FAILED", OffsetDateTime.now(), Map.of(), safe(error)));
        }
    }

    private String verdict(EvaluationService.EvalCase value, Outcome outcome, RagMetricsCalculator.Metrics metrics) {
        if (value.answerable() == outcome.abstained()) return "WRONG_ABSTENTION";
        if (!value.answerable()) return "PASS";
        return metrics.recall() > 0 && outcome.citationValid() && outcome.faithful() ? "PASS" : "FAIL";
    }
    private double average(double total, int count) { return count == 0 ? 0 : total / count; }
    private String safe(RuntimeException error) {
        String message = error.getMessage();
        return message == null ? error.getClass().getSimpleName() : message.substring(0, Math.min(300, message.length()));
    }

    public interface Pipeline {
        Outcome evaluate(long kbId, String question, long operatorId);
    }
    public interface Repository {
        EvaluationService.Dataset findDataset(long tenantId, long datasetId);
        List<EvaluationService.EvalCase> listCases(long tenantId, long datasetId);
        Run insertRun(Run value);
        Run findRun(long tenantId, long runId);
        void updateRun(Run value);
        void insertResult(Result value);
    }
    public record Outcome(String traceId, List<String> rankedChunkIds, String confidenceState,
                          boolean citationValid, boolean faithful, boolean abstained) {}
    public record Run(long id, long tenantId, long datasetId, String pipelineVersion, String status,
                      long startedBy, OffsetDateTime startedAt, OffsetDateTime finishedAt,
                      Map<String, Double> summaryMetrics, String errorSummary) {
        public Run withId(long id) { return new Run(id, tenantId, datasetId, pipelineVersion, status, startedBy,
                startedAt, finishedAt, summaryMetrics, errorSummary); }
        public Run withStatus(String status, OffsetDateTime finishedAt, Map<String, Double> metrics, String error) {
            return new Run(id, tenantId, datasetId, pipelineVersion, status, startedBy, startedAt,
                    finishedAt, Map.copyOf(metrics), error);
        }
    }
    public record Result(long id, long runId, long caseId, String traceId,
                         Map<String, Double> metrics, String verdict) {}
}
