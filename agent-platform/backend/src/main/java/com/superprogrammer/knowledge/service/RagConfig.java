package com.superprogrammer.knowledge.service;

import lombok.Getter;
import org.springframework.stereotype.Component;

/**
 * RAG 检索 token 预算与召回参数（v6 §7）。
 * 单默认档 + 高精度开关（未启用扇出预算账，v6 §11 砍）。
 *
 * B1: effectiveContextCap = min(maxContextTokens, modelMaxContext - answerTokenReserve)。
 */
@Getter
@Component
public class RagConfig {

    // ---- §7.2 token 预算 ----
    private final int maxContextTokens = 6000;
    private final int modelMaxContext = 32000;
    private final int answerTokenReserve = 1200;

    // ---- 召回/重排上界 ----
    private final int maxL0Candidates = 40;   // step5 dense top-N
    private final int denseTopM = 8;          // step6 进 expand 的 L0 数
    private final int denseTopD = 5;          // step6 进 expand 的文档数
    private final int perDocL2Cap = 20;       // 每文档 L2 候选上限
    private final int maxL2Read = 3;          // step6 rerank 取 top-K（最终证据数）
    private final int maxRerankPairs = 100;   // B3：D×cap=5×20=100

    // ---- L1 注入 ----
    private final int l1PerDocTokenCap = 250;

    // ---- abstention / rerank 代理（DEV-rerank）----
    private final float abstainThreshold = 0.5f;       // A2：父 L0 cosine sim < 此 → 拒答
    private final float bm25BoostMax = 0.10f;          // BM25 boost 上界（DEV-rerank）

    // ---- HNSW ----
    private final int hnswEfSearch = 0;     // 0 = 不设（用默认）；>0 → SET LOCAL hnsw.ef_search

    // ---- 生成 ----
    private final double chatTemperature = 0.2;
    private final int chatMaxTokens = 1200;

    /** B1：effectiveContextCap = min(maxContextTokens, modelMaxContext - answerTokenReserve)。 */
    public int computeEffectiveContextCap() {
        return Math.min(maxContextTokens, modelMaxContext - answerTokenReserve);
    }

    // ---- 个人记忆冲突解决（V27，设计 §6/§7/§8）----
    // Phase1 静态默认值（YAGNI：未接 @ConfigurationProperties/yml 覆盖）
    public static final double MEMORY_BLOCK_SIM_THRESHOLD = 0.6;   // 归块门槛
    public static final int    MEMORY_CONFLICT_EXPIRE_MIN = 10;    // PENDING 超时（分钟）

    /** 个人记忆标签「大类」base vocab 兜底（system_settings.memory.tag.vocab 缺失/非法时回退）。
     *  同概念内容共用同一 topic → UNIQUE(user,subject,topic) 路径①自动合并，杜绝细标签。
     *  「其他」为哨兵：词表外内容填它 → 触发 needs_review 交用户裁决。 */
    public static final java.util.List<String> MEMORY_TAG_VOCAB_DEFAULT = java.util.List.of(
            "个人信息", "工作职业", "学习教育", "兴趣爱好", "生活日常", "旅行出行",
            "社交人际", "健康医疗", "财务理财", "技术技能", "创作内容", "教学方法", "其他");
}
