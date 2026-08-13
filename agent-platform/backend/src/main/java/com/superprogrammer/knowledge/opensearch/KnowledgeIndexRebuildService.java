package com.superprogrammer.knowledge.opensearch;

/**
 * 索引快照重建编排器。创建物理索引、登记重建批次并观察任务终态；
 * 具体 OpenSearch/PG 操作由 RebuildGateway 承担，便于保证控制面状态可持久化。
 */
@org.springframework.stereotype.Service
public class KnowledgeIndexRebuildService {
    private final RebuildGateway gateway;
    private final int embeddingDimension;
    private final String pipelineVersion;

    public KnowledgeIndexRebuildService(RebuildGateway gateway,
            @org.springframework.beans.factory.annotation.Value("${rag.index.embedding-dimension:2048}") int embeddingDimension,
            @org.springframework.beans.factory.annotation.Value("${rag.index.pipeline-version:rag-index-v1}") String pipelineVersion) {
        this.gateway = gateway;
        this.embeddingDimension = embeddingDimension;
        this.pipelineVersion = pipelineVersion;
    }

    public RebuildStatus start(long kbId, String snapshotId, boolean dryRun) {
        KnowledgeIndexSchema schema = new KnowledgeIndexSchema(embeddingDimension, pipelineVersion, snapshotId);
        if (dryRun) {
            return new RebuildStatus(kbId, snapshotId, "DRY_RUN", 0, 0, 0, 0);
        }
        String physicalIndex = gateway.create(kbId, schema);
        gateway.begin(kbId, snapshotId, physicalIndex);
        int total = gateway.enqueue(kbId, snapshotId, pipelineVersion);
        return new RebuildStatus(kbId, snapshotId, "BUILDING", total, 0, 0, 0);
    }

    public RebuildStatus status(long kbId, String snapshotId) {
        JobCounts counts = gateway.counts(kbId, snapshotId);
        String state;
        if (counts.cancelled() > 0) {
            state = "CANCELLED";
        } else if (counts.failed() > 0) {
            gateway.markFailed(kbId, snapshotId);
            state = "FAILED";
        } else if (counts.done() >= counts.total()) {
            gateway.markReady(kbId, snapshotId);
            state = "READY";
        } else {
            state = "BUILDING";
        }
        return new RebuildStatus(kbId, snapshotId, state, counts.total(), counts.done(),
                counts.failed(), counts.cancelled());
    }

    public RebuildStatus cancel(long kbId, String snapshotId) {
        gateway.cancel(kbId, snapshotId);
        return status(kbId, snapshotId);
    }

    public RebuildStatus latest(long kbId) {
        String snapshotId = gateway.latestSnapshot(kbId);
        return snapshotId == null ? null : status(kbId, snapshotId);
    }

    public interface RebuildGateway {
        String create(long kbId, KnowledgeIndexSchema schema);
        void begin(long kbId, String snapshotId, String physicalIndex);
        int enqueue(long kbId, String snapshotId, String pipelineVersion);
        JobCounts counts(long kbId, String snapshotId);
        void markReady(long kbId, String snapshotId);
        void markFailed(long kbId, String snapshotId);
        void cancel(long kbId, String snapshotId);
        String latestSnapshot(long kbId);
    }

    public record JobCounts(int total, int done, int failed, int cancelled) {}

    public record RebuildStatus(long knowledgeBaseId, String snapshotId, String state,
                                int total, int completed, int failed, int cancelled) {}
}
