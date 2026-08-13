package com.superprogrammer.knowledge.opensearch;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIndexOperationsServiceTest {

    @Test
    void persistsRegisteredSnapshotsAndUsesOnlyRegisteredTargets() throws Exception {
        FakeStore store = new FakeStore();
        RecordingAliasService aliases = new RecordingAliasService();
        KnowledgeIndexOperationsService service = new KnowledgeIndexOperationsService(aliases, store);

        service.registerSnapshot(7L, "snap-a", "kb-7-chunks-snap-a-pipe-v2");
        service.registerSnapshot(7L, "snap-b", "kb-7-chunks-snap-b-pipe-v2");
        assertThrows(IllegalArgumentException.class,
                () -> service.switchSnapshot(7L, "unknown", true));

        service.switchSnapshot(7L, "snap-a", true);
        service.switchSnapshot(7L, "snap-b", true);

        KnowledgeIndexOperationsService restarted = new KnowledgeIndexOperationsService(aliases, store);
        assertEquals("snap-b", restarted.status(7L).activeSnapshotId());
        assertEquals("snap-a", restarted.status(7L).previousSnapshotId());
        assertEquals(2, aliases.plans.size());
        assertEquals("kb-7-chunks-snap-b-pipe-v2", aliases.plans.get(1).get(2).index());
    }

    private static final class FakeStore implements KnowledgeIndexOperationsService.SnapshotStore {
        private final java.util.Map<Long, KnowledgeIndexOperationsService.SnapshotRecord> records = new java.util.HashMap<>();
        private final java.util.Map<Long, java.util.Set<String>> registered = new java.util.HashMap<>();
        private final java.util.Map<String, String> physical = new java.util.HashMap<>();

        @Override public KnowledgeIndexOperationsService.SnapshotRecord load(long kbId) { return records.get(kbId); }
        @Override public void save(KnowledgeIndexOperationsService.SnapshotRecord record) { records.put(record.knowledgeBaseId(), record); }
        @Override public void register(long kbId, String snapshotId, String physicalIndex) { registered.computeIfAbsent(kbId, ignored -> new java.util.HashSet<>()).add(snapshotId); physical.put(kbId + ":" + snapshotId, physicalIndex); }
        @Override public boolean registered(long kbId, String snapshotId) { return registered.getOrDefault(kbId, java.util.Set.of()).contains(snapshotId); }
        @Override public String physicalIndex(long kbId, String snapshotId) { return physical.get(kbId + ":" + snapshotId); }
    }

    private static final class RecordingAliasService extends IndexAliasService {
        private final List<List<AliasAction>> plans = new ArrayList<>();
        @Override public void execute(List<AliasAction> plan) { plans.add(plan); }
    }
}
