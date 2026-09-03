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

    /** C1 step6.5 文档关系图扩展（MUST/MAY 带出 + 相关文档区）。关 → 检索行为回到无关系基线。 */
    private Relation relation = new Relation();

    /** C2 附件命中注入（attachmentText 直注 / 图片 VLM 实时识图+Redis 缓存）。关 → 附件型证据只带描述。 */
    private Attachment attachment = new Attachment();

    @Data
    public static class Attachment {
        /** 注入总开关（运维 kill switch：VLM 通道故障/费用异常时关掉即回「仅描述」基线，无需回滚发版）。 */
        private boolean enabled = true;
        /** 单附件注入内容上限（字符）。超限截断并标注「可下载原件」。 */
        private int maxInjectChars = 8000;
        /** 图片实时识图超时（ms）：超时降级为仅描述 + 「原件内容暂缺」标注，不阻塞检索主链。 */
        private int visionTimeoutMs = 2500;
        /** 图片识图 max_tokens（识图文本即注入内容，无需长文）。 */
        private int visionMaxTokens = 1024;
        /** 识图结果 Redis 缓存 TTL（天）——文档删除/换版后自然过期兜底，无主动清理。 */
        private int visionCacheTtlDays = 30;
        /** 识图提示词版本号（进缓存 key：改提示词 → key 变 → 全量重新识图）。 */
        private String visionPromptVersion = "v1";
    }

    @Data
    public static class Relation {
        /** 扩展开关（运维 kill switch：边数据异常/误建边风暴时关掉即回基线，无需回滚发版）。 */
        private boolean enabled = true;
        /** 每带出文档的 L2 节点上限（独立于主池 perDocL2Cap，可单独收紧）。 */
        private int perDocL2Cap = 5;
        /** 共召回建议 worker 开关（关 → 不再生成新建议；已有建议照常可采纳/忽略）。 */
        private boolean suggestionEnabled = true;
        /** 建议门槛：同 query 下两文档共现 ≥ 此次数才建议（默认 3，规格 §3.3）。 */
        private int suggestionMinCoRecall = 3;
        /** 建议扫描窗口（天）：只统计近 N 天 trace，窗口外共现不再累计。 */
        private int suggestionLookbackDays = 14;
    }

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
