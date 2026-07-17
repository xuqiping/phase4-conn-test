package com.superprogrammer.chat.service.internal;

import com.superprogrammer.chat.dto.MemoryBlockHit;
import com.superprogrammer.chat.mapper.UserMemoryMapper;
import com.superprogrammer.knowledge.service.RagConfig;
import com.superprogrammer.knowledge.util.HalfVecUtil;
import com.superprogrammer.llm.LlmGateway;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 记忆块分类器（embed 聚类，免维护枚举）。
 * 新事实 embed → 同 user 最近记忆余弦近邻 → sim≥阈值继承 block_label，否则用候选名种新块。
 * 块名定一次后冻结（后续靠向量继承，不重命名 → 无漂移）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MemoryBlockClassifier {

    private final LlmGateway llmGateway;
    private final UserMemoryMapper memoryMapper;

    /** 结果：归属 block_label + 已算好的 halfvec 文本（复用给后续 insert，免二次 embed）。 */
    public record BlockResult(String blockLabel, String halfvec) {}

    /**
     * 给一条事实文本（调用方拼 key:value）定块。限 writeScope（同 scope 内聚类，不跨 scope 归块）。
     * @param writeScope 写目标 scope（块聚类限此可见集）
     * @param candidateBlock 抽取 LLM 给的候选块名（仅 sim<阈值 开新块时采用）
     */
    public BlockResult classify(MemoryScope writeScope, String factText, String candidateBlock) {
        float[] vec = llmGateway.embed(factText, RagConfig.MEMORY_EMBED_MODEL);
        // 安全审计 #6：记忆 fact 原文属 PII，降 DEBUG（生产 INFO 不打）。
        log.debug("classify factText.len={} vecLen={}", factText == null ? 0 : factText.length(), vec == null ? -1 : vec.length);
        String halfvec = HalfVecUtil.toHalfVec(vec);
        MemoryBlockHit hit = memoryMapper.findNearestBlock(writeScope.userId(), halfvec,
                writeScope.includeGlobal(), writeScope.safeProjectIds());
        if (hit != null && hit.similarity() >= RagConfig.MEMORY_BLOCK_SIM_THRESHOLD) {
            return new BlockResult(hit.blockLabel(), halfvec);
        }
        String label = (candidateBlock == null || candidateBlock.isBlank())
                ? "其他" : candidateBlock.trim();
        return new BlockResult(label, halfvec);
    }
}
