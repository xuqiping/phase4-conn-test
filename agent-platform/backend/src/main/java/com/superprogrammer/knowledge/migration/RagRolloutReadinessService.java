package com.superprogrammer.knowledge.migration;

import com.superprogrammer.knowledge.opensearch.KnowledgeIndexOperationsService;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class RagRolloutReadinessService {
    private final KnowledgeIndexOperationsService indexOperationsService;
    private final Map<Long, Boolean> releaseGateStates = new ConcurrentHashMap<>();
    private final Map<Long, Boolean> reconciliationStates = new ConcurrentHashMap<>();

    public RagRolloutReadinessService(KnowledgeIndexOperationsService indexOperationsService) {
        this.indexOperationsService = indexOperationsService;
    }

    public RagRolloutService.Readiness readiness(long kbId) {
        boolean indexHealthy = "READY".equals(indexOperationsService.status(kbId).state());
        return new RagRolloutService.Readiness(
                releaseGateStates.getOrDefault(kbId, false),
                indexHealthy,
                reconciliationStates.getOrDefault(kbId, false));
    }

    public void recordReleaseGate(long kbId, boolean passed) {
        releaseGateStates.put(kbId, passed);
    }

    public void recordReconciliation(long kbId, boolean passed) {
        reconciliationStates.put(kbId, passed);
    }
}
