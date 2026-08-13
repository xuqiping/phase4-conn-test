package com.superprogrammer.knowledge.migration;

import com.superprogrammer.knowledge.opensearch.KnowledgeIndexOperationsService;
import com.superprogrammer.knowledge.evaluation.EvaluationRunService;
import com.superprogrammer.knowledge.evaluation.PostgresEvaluationRepository;
import com.superprogrammer.knowledge.evaluation.ReleaseGateService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RagRolloutReadinessService {
    private final KnowledgeIndexOperationsService indexOperationsService;
    private final PostgresEvaluationRepository evaluationRepository;
    private final ReleaseGateService releaseGateService;
    private final Map<Long, Boolean> reconciliationStates = new ConcurrentHashMap<>();

    public RagRolloutReadinessService(KnowledgeIndexOperationsService indexOperationsService,
                                      PostgresEvaluationRepository evaluationRepository,
                                      ReleaseGateService releaseGateService) {
        this.indexOperationsService = indexOperationsService;
        this.evaluationRepository = evaluationRepository;
        this.releaseGateService = releaseGateService;
    }

    public RagRolloutService.Readiness readiness(long kbId) {
        boolean indexHealthy = "READY".equals(indexOperationsService.status(kbId).state());
        EvaluationRunService.Run run=evaluationRepository.findLatestCompletedRun(1L,kbId);
        boolean releasePassed=run!=null && releaseGateService.evaluate(run.summaryMetrics()).passed();
        return new RagRolloutService.Readiness(
                releasePassed,
                indexHealthy,
                reconciliationStates.getOrDefault(kbId, false));
    }

    public void recordReconciliation(long kbId, boolean passed) {
        reconciliationStates.put(kbId, passed);
    }
}
