package com.superprogrammer.knowledge.opensearch;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIndexRebuildServiceTest {

    @Test
    void createsPhysicalIndexEnqueuesSnapshotJobsAndBecomesReadyOnlyAfterSuccess() {
        FakeGateway gateway = new FakeGateway();
        KnowledgeIndexRebuildService service = new KnowledgeIndexRebuildService(gateway, 2048, "pipe-v2");

        KnowledgeIndexRebuildService.RebuildStatus started = service.start(7L, "snap-1", false);
        assertEquals("BUILDING", started.state());
        assertEquals(12, started.total());
        assertEquals("kb-7-chunks-snap-1-pipe-v2", gateway.physicalIndex);

        gateway.done = 11;
        assertEquals("BUILDING", service.status(7L, "snap-1").state());
        gateway.done = 12;
        assertEquals("READY", service.status(7L, "snap-1").state());
        assertTrue(gateway.ready);
    }

    @Test
    void failureAndCancelAreTerminalAndDryRunDoesNotMutate() {
        FakeGateway gateway = new FakeGateway();
        KnowledgeIndexRebuildService service = new KnowledgeIndexRebuildService(gateway, 2048, "pipe-v2");

        assertEquals("DRY_RUN", service.start(7L, "preview", true).state());
        assertNull(gateway.physicalIndex);

        service.start(7L, "snap-2", false);
        gateway.failed = 1;
        assertEquals("FAILED", service.status(7L, "snap-2").state());

        gateway.failed = 0;
        service.cancel(7L, "snap-2");
        assertEquals("CANCELLED", service.status(7L, "snap-2").state());
    }

    @Test
    void latestReturnsPersistedRebuildProgressAfterRefresh() {
        FakeGateway gateway = new FakeGateway();
        gateway.latestSnapshot = "snap-latest";
        gateway.done = 4;
        KnowledgeIndexRebuildService service = new KnowledgeIndexRebuildService(gateway, 2048, "pipe-v2");

        KnowledgeIndexRebuildService.RebuildStatus status = service.latest(7L);

        assertEquals("snap-latest", status.snapshotId());
        assertEquals(4, status.completed());
    }

    private static final class FakeGateway implements KnowledgeIndexRebuildService.RebuildGateway {
        String physicalIndex;
        int done;
        int failed;
        boolean cancelled;
        boolean ready;
        String latestSnapshot;

        @Override public String create(long kbId, KnowledgeIndexSchema schema) { return physicalIndex = schema.physicalIndexName(kbId); }
        @Override public void begin(long kbId, String snapshotId, String physicalIndex) {}
        @Override public int enqueue(long kbId, String snapshotId, String pipelineVersion) { return 12; }
        @Override public KnowledgeIndexRebuildService.JobCounts counts(long kbId, String snapshotId) { return new KnowledgeIndexRebuildService.JobCounts(12, done, failed, cancelled ? 12 : 0); }
        @Override public void markReady(long kbId, String snapshotId) { ready = true; }
        @Override public void markFailed(long kbId, String snapshotId) {}
        @Override public void cancel(long kbId, String snapshotId) { cancelled = true; }
        @Override public String latestSnapshot(long kbId) { return latestSnapshot; }
    }
}
