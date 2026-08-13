package com.superprogrammer.knowledge.opensearch;

import com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO;
import org.springframework.stereotype.Service;

import java.io.IOException;

@Service
public class KnowledgeIndexOperationsService {
    private final IndexAliasService aliasService;
    private final SnapshotStore store;

    public KnowledgeIndexOperationsService(IndexAliasService aliasService, SnapshotStore store) {
        this.aliasService = aliasService;
        this.store = store;
    }

    public KnowledgeIndexStatusVO status(long kbId) {
        SnapshotRecord state = store.load(kbId);
        return new KnowledgeIndexStatusVO(kbId, state == null ? "NOT_INITIALIZED" : "READY",
                IndexAliasService.readAlias(kbId), IndexAliasService.writeAlias(kbId),
                state == null ? null : state.activeSnapshotId(),
                state == null ? null : state.previousSnapshotId());
    }

    public KnowledgeIndexStatusVO registerRebuild(long kbId, String snapshotId, boolean dryRun) {
        validateSnapshot(snapshotId);
        if (!dryRun) store.register(kbId, snapshotId);
        return status(kbId);
    }

    public KnowledgeIndexStatusVO switchSnapshot(long kbId, String snapshotId, boolean confirmed) throws IOException {
        requireConfirmed(confirmed);
        validateSnapshot(snapshotId);
        if (!store.registered(kbId, snapshotId)) {
            throw new IllegalArgumentException("snapshot 未登记，禁止切换");
        }
        SnapshotRecord current = store.load(kbId);
        String target = physical(kbId, snapshotId);
        if (current == null || current.activeSnapshotId() == null) {
            aliasService.execute(aliasService.activatePlan(kbId, target));
        } else if (!snapshotId.equals(current.activeSnapshotId())) {
            aliasService.execute(aliasService.switchPlan(kbId,
                    physical(kbId, current.activeSnapshotId()), target));
        }
        long version = current == null ? 1 : current.configVersion() + 1;
        store.save(new SnapshotRecord(kbId, snapshotId,
                current == null ? null : current.activeSnapshotId(), version, null));
        return status(kbId);
    }

    public KnowledgeIndexStatusVO rollback(long kbId, boolean confirmed) throws IOException {
        requireConfirmed(confirmed);
        SnapshotRecord current = store.load(kbId);
        if (current == null || current.previousSnapshotId() == null) {
            throw new IllegalStateException("没有可回滚的已登记 snapshot");
        }
        aliasService.execute(aliasService.rollbackPlan(kbId,
                physical(kbId, current.activeSnapshotId()), physical(kbId, current.previousSnapshotId())));
        store.save(new SnapshotRecord(kbId, current.previousSnapshotId(), current.activeSnapshotId(),
                current.configVersion() + 1, null));
        return status(kbId);
    }

    private static void validateSnapshot(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}")) {
            throw new IllegalArgumentException("snapshotId 不合法");
        }
    }

    private static void requireConfirmed(boolean confirmed) {
        if (!confirmed) throw new IllegalArgumentException("需要二次确认");
    }

    private static String physical(long kbId, String snapshot) {
        return "kb-" + kbId + "-chunks-" + snapshot;
    }

    public interface SnapshotStore {
        SnapshotRecord load(long kbId);
        void save(SnapshotRecord record);
        void register(long kbId, String snapshotId);
        boolean registered(long kbId, String snapshotId);
    }

    public record SnapshotRecord(long knowledgeBaseId, String activeSnapshotId,
                                 String previousSnapshotId, long configVersion, Long updatedBy) {}
}
