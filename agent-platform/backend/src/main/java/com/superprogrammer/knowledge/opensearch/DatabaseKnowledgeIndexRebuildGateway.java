package com.superprogrammer.knowledge.opensearch;

import com.superprogrammer.knowledge.mapper.KnowledgeIndexJobMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

import java.io.IOException;

@Component
public class DatabaseKnowledgeIndexRebuildGateway implements KnowledgeIndexRebuildService.RebuildGateway {
    private final ObjectProvider<KnowledgeIndexManager> managerProvider;
    private final KnowledgeIndexSnapshotMapper snapshots;
    private final KnowledgeIndexJobMapper jobs;

    public DatabaseKnowledgeIndexRebuildGateway(ObjectProvider<KnowledgeIndexManager> managerProvider,
                                                KnowledgeIndexSnapshotMapper snapshots,
                                                KnowledgeIndexJobMapper jobs) {
        this.managerProvider = managerProvider;
        this.snapshots = snapshots;
        this.jobs = jobs;
    }

    @Override
    public String create(long kbId, KnowledgeIndexSchema schema) {
        KnowledgeIndexManager manager = managerProvider.getIfAvailable();
        if (manager == null) throw new IllegalStateException("OpenSearch 未启用，无法启动实际重建");
        try {
            return manager.create(kbId, schema);
        } catch (IOException e) {
            throw new IllegalStateException("OpenSearch 创建物理索引失败", e);
        }
    }

    @Override public void begin(long kbId, String snapshotId, String physicalIndex) {
        snapshots.begin(kbId, snapshotId, physicalIndex);
    }
    @Override public int enqueue(long kbId, String snapshotId, String pipelineVersion) {
        String physical = snapshots.physicalIndex(kbId, snapshotId);
        if (physical == null || physical.isBlank()) throw new IllegalStateException("快照缺少物理索引登记");
        return jobs.enqueueSnapshotJobs(kbId, snapshotId, physical, pipelineVersion);
    }
    @Override public KnowledgeIndexRebuildService.JobCounts counts(long kbId, String snapshotId) {
        KnowledgeIndexJobMapper.SnapshotJobCounts c = jobs.countSnapshotJobs(kbId, snapshotId);
        return c == null ? new KnowledgeIndexRebuildService.JobCounts(0, 0, 0, 0)
                : new KnowledgeIndexRebuildService.JobCounts(c.total(), c.done(), c.failed(), c.cancelled());
    }
    @Override public void markReady(long kbId, String snapshotId) { snapshots.updateStatus(kbId, snapshotId, "READY"); }
    @Override public void markFailed(long kbId, String snapshotId) { snapshots.updateStatus(kbId, snapshotId, "FAILED"); }
    @Override public void cancel(long kbId, String snapshotId) {
        jobs.cancelSnapshotJobs(kbId, snapshotId);
        snapshots.updateStatus(kbId, snapshotId, "CANCELLED");
    }
    @Override public String latestSnapshot(long kbId) { return snapshots.latestSnapshot(kbId); }
}
