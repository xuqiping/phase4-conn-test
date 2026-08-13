package com.superprogrammer.knowledge.opensearch;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OpenSearchReconciliationServiceTest {

    @Test
    void classifiesMissingOrphanHashAndAclDrift() {
        OpenSearchReconciliationService service = new OpenSearchReconciliationService();
        List<OpenSearchReconciliationService.NodeSnapshot> pg = List.of(
                node(1, "h1", List.of("tenant:1")),
                node(2, "h2", List.of("tenant:1")),
                node(3, "h3", List.of("tenant:1")));
        List<OpenSearchReconciliationService.NodeSnapshot> os = List.of(
                node(1, "old", List.of("tenant:1")),
                node(2, "h2", List.of("tenant:1", "user:9")),
                node(4, "h4", List.of("tenant:1")));

        OpenSearchReconciliationService.Report report = service.compare(pg, os);

        assertEquals(List.of(3L), report.missingIds());
        assertEquals(List.of(4L), report.orphanIds());
        assertEquals(List.of(1L), report.hashDriftIds());
        assertEquals(List.of(2L), report.aclDriftIds());
    }

    @Test
    void dryRunBuildsRepairPlanWithoutExecutingMutations() {
        OpenSearchReconciliationService service = new OpenSearchReconciliationService();
        OpenSearchReconciliationService.Report report = new OpenSearchReconciliationService.Report(
                List.of(3L), List.of(4L), List.of(1L), List.of(2L));

        OpenSearchReconciliationService.RepairPlan plan = service.plan(report, true);

        assertEquals(true, plan.dryRun());
        assertEquals(List.of(1L, 2L, 3L), plan.reindexIds());
        assertEquals(List.of(4L), plan.deleteIds());
    }

    private static OpenSearchReconciliationService.NodeSnapshot node(long id, String hash, List<String> acl) {
        return new OpenSearchReconciliationService.NodeSnapshot(id, hash, acl);
    }
}
