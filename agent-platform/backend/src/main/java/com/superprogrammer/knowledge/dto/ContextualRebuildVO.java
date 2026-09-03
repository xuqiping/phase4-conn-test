package com.superprogrammer.knowledge.dto;

/**
 * WP3 C4 存量上下文增强：成本预估 / 应用结果。
 * docCount=参与文档数；chunkCount=参与 L2 分块数（重嵌量）；llmCallCount=LLM 定位表调用次数（=文档数）；
 * skippedDone=中断可续跳过（整文档已完成）；skippedAttachment=附件描述召回豁免；enqueuedJobs=新入队 REINDEX 数。
 */
public record ContextualRebuildVO(int docCount, int chunkCount, int llmCallCount,
                                  int appliedDocs, int skippedDone, int skippedAttachment,
                                  int enqueuedJobs, boolean dryRun) {

    public static ContextualRebuildVO estimate(int docCount, int chunkCount, int skippedAttachment) {
        return new ContextualRebuildVO(docCount, chunkCount, docCount, 0, 0, skippedAttachment, 0, true);
    }

    public static ContextualRebuildVO applied(int totalDocs, int appliedDocs, int skippedDone,
                                       int skippedAttachment, int enqueuedJobs) {
        return new ContextualRebuildVO(totalDocs, 0, appliedDocs, appliedDocs, skippedDone,
                skippedAttachment, enqueuedJobs, false);
    }
}
