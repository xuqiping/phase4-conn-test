package com.superprogrammer.knowledge.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * RAG 召回升级旋钮（多路扩展 + RRF + 软拒答，prefix {@code rag.recall}）。
 * 仿 {@link AnswerCacheProperties}：{@code @Component + @ConfigurationProperties}，yml 可覆盖、默认值内置。
 *
 * <p>承载新召回语义；{@link com.superprogrammer.knowledge.service.RagConfig} 的 B1/B3 token 不变量常量不动
 * （那些是检索管线多步不变式的数值锚，有专属测）。仅 abstain 语义从此迁出（见 {@link Abstain}）。
 */
@Data
@Component
@ConfigurationProperties(prefix = "rag.recall")
public class RagRecallProperties {

    /** 总开关（关 → 不做多路扩展，行为同升级前单 query）。 */
    private boolean enabled = true;

    /** query 多路扩展。 */
    private Expansion expansion = new Expansion();

    /** HyDE（假想答案 embedding，补 query 与文档表达差异）。 */
    private Hyde hyde = new Hyde();

    /** RRF 融合常数与通道权重（Phase2/3 多通道时生效）。 */
    private Rrf rrf = new Rrf();

    /** 软拒答双阈（替旧 0.5 单悬崖）。 */
    private Abstain abstain = new Abstain();

    /** 启发式精排权重（Phase2/3 多通道 blend；Phase1 仅 parentSim 主导）。 */
    private Rerank rerank = new Rerank();

    @Data
    public static class Expansion {
        /** 扩展开关（关 → 仅规范 query）。 */
        private boolean enabled = true;
        /** LLM 生成的释义条数（不含规范 query 与 HyDE）。 */
        private int count = 2;
    }

    @Data
    public static class Hyde {
        private boolean enabled = true;
    }

    @Data
    public static class Rrf {
        /** RRF k 常数（标准 60）。 */
        private int k = 60;
        /** L0 向量通道权重。 */
        private double weightL0Vector = 1.0;
        /** L1 向量通道权重（Phase3）。 */
        private double weightL1Vector = 0.8;
        /** jieba-BM25 通道权重（Phase2）。 */
        private double weightBm25 = 0.6;
    }

    @Data
    public static class Abstain {
        /** best sim < hard → 拒答（finishAbstain LOW_CONFIDENCE）。 */
        private double hard = 0.30;
        /** hard ≤ best sim < soft → 灰区：照回答但标 lowConfidence、不写缓存。 */
        private double soft = 0.45;
    }

    @Data
    public static class Rerank {
        /** RRF 归一分权重。 */
        private double weightRrf = 0.55;
        /** 父 L0 余弦权重。 */
        private double weightParentSim = 0.35;
        /** 词法命中权重（Phase2 bm25 命中标记）。 */
        private double weightLexical = 0.10;
    }
}
