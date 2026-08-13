package com.superprogrammer.knowledge.opensearch;

import org.springframework.stereotype.Component;

@Component
public class DatabaseSnapshotStore implements KnowledgeIndexOperationsService.SnapshotStore {
    private final KnowledgeIndexSnapshotMapper mapper;

    public DatabaseSnapshotStore(KnowledgeIndexSnapshotMapper mapper) {
        this.mapper = mapper;
    }

    @Override public KnowledgeIndexOperationsService.SnapshotRecord load(long kbId) { return mapper.load(kbId); }
    @Override public void save(KnowledgeIndexOperationsService.SnapshotRecord record) { mapper.save(record); }
    @Override public void register(long kbId, String snapshotId) { mapper.register(kbId, snapshotId); }
    @Override public boolean registered(long kbId, String snapshotId) { return mapper.registered(kbId, snapshotId); }
}
