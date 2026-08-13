package com.superprogrammer.knowledge.opensearch;

import com.superprogrammer.knowledge.dto.KnowledgeIndexStatusVO;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KnowledgeIndexOperationsService {

    private final IndexAliasService aliasService;
    private final Map<Long, SnapshotState> states = new ConcurrentHashMap<>();

    public KnowledgeIndexOperationsService(IndexAliasService aliasService) { this.aliasService = aliasService; }

    public KnowledgeIndexStatusVO status(long kbId) {
        SnapshotState state = states.get(kbId);
        return new KnowledgeIndexStatusVO(kbId, state == null ? "NOT_INITIALIZED" : "READY",
                IndexAliasService.readAlias(kbId), IndexAliasService.writeAlias(kbId),
                state == null ? null : state.active(), state == null ? null : state.previous());
    }

    public KnowledgeIndexStatusVO registerRebuild(long kbId, String snapshotId, boolean dryRun) {
        validateSnapshot(snapshotId);
        if (!dryRun) states.putIfAbsent(kbId, new SnapshotState(null, null));
        return status(kbId);
    }

    public KnowledgeIndexStatusVO switchSnapshot(long kbId, String snapshotId, boolean confirmed) throws java.io.IOException {
        requireConfirmed(confirmed);
        validateSnapshot(snapshotId);
        SnapshotState current = states.getOrDefault(kbId, new SnapshotState(null, null));
        String target = physical(kbId, snapshotId);
        if (current.active() != null) aliasService.execute(aliasService.switchPlan(kbId, physical(kbId, current.active()), target));
        states.put(kbId, new SnapshotState(snapshotId, current.active()));
        return status(kbId);
    }

    public KnowledgeIndexStatusVO rollback(long kbId, boolean confirmed) throws java.io.IOException {
        requireConfirmed(confirmed);
        SnapshotState current = states.get(kbId);
        if (current == null || current.previous() == null) throw new IllegalStateException("没有可回滚的已登记 snapshot");
        aliasService.execute(aliasService.rollbackPlan(kbId, physical(kbId, current.active()), physical(kbId, current.previous())));
        states.put(kbId, new SnapshotState(current.previous(), current.active()));
        return status(kbId);
    }

    private static void validateSnapshot(String id) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("snapshotId 不合法");
    }
    private static void requireConfirmed(boolean confirmed) { if (!confirmed) throw new IllegalArgumentException("需要二次确认"); }
    private static String physical(long kbId, String snapshot) { return "kb-" + kbId + "-chunks-" + snapshot; }
    private record SnapshotState(String active, String previous) {}
}
