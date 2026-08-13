package com.superprogrammer.knowledge.opensearch;

import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class DatabaseKnowledgeIndexRebuildGatewayTest {
    @Mock private KnowledgeIndexManager manager;
    @Mock private KnowledgeIndexSnapshotMapper snapshots;
    @Mock private KnowledgeIndexJobMapper jobs;
    @Mock private ObjectProvider<KnowledgeIndexManager> managerProvider;

    @Test
    void persistsPhysicalIndexAndEnqueuesSnapshotScopedJobs() throws Exception {
        when(managerProvider.getIfAvailable()).thenReturn(manager);
        KnowledgeIndexSchema schema = new KnowledgeIndexSchema(2048, "pipe-v2", "snap-1");
        when(manager.create(7L, schema)).thenReturn("kb-7-chunks-snap-1-pipe-v2");
        when(snapshots.physicalIndex(7L, "snap-1")).thenReturn("kb-7-chunks-snap-1-pipe-v2");
        when(jobs.enqueueSnapshotJobs(7L, "snap-1", "kb-7-chunks-snap-1-pipe-v2", "pipe-v2"))
                .thenReturn(12);
        DatabaseKnowledgeIndexRebuildGateway gateway = new DatabaseKnowledgeIndexRebuildGateway(
                managerProvider, snapshots, jobs);

        String physical = gateway.create(7L, schema);
        gateway.begin(7L, "snap-1", physical);
        int total = gateway.enqueue(7L, "snap-1", "pipe-v2");

        assertEquals(12, total);
        verify(snapshots).begin(7L, "snap-1", "kb-7-chunks-snap-1-pipe-v2");
    }
}
